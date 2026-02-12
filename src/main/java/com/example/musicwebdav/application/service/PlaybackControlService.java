package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.PlaybackControlRequest;
import com.example.musicwebdav.api.response.NowPlayingStatusResponse;
import com.example.musicwebdav.api.response.NowPlayingTrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlaybackControlService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackControlService.class);

    private final TrackMapper trackMapper;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, PlaybackStateSnapshot> stateByActor = new ConcurrentHashMap<>();

    public PlaybackControlService(TrackMapper trackMapper, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.trackMapper = trackMapper;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public NowPlayingStatusResponse handleControl(String actor, PlaybackControlRequest request) {
        if (request == null) {
            throw new BusinessException("400", "请求参数不合法", "请检查输入参数后重试");
        }
        String normalizedActor = normalizeActor(actor);
        String command = normalizeCommand(request.getCommand());
        long startedAtNanos = System.nanoTime();
        recordCounter("music.playback.control.click", "command", command);
        logControlClick(normalizedActor, command, request);
        try {
            PlaybackStateSnapshot updated = stateByActor.compute(normalizedActor, (key, current) ->
                    applyCommand(current, request, command, normalizedActor));
            recordCounter("music.playback.control.result", "command", command, "outcome", "success");
            logControlResult(normalizedActor, command, "success", null);
            return toResponse(updated, command);
        } catch (BusinessException e) {
            recordCounter("music.playback.control.result", "command", command, "outcome", "failed", "code",
                    safeCode(e.getCode()));
            logControlResult(normalizedActor, command, "failed", safeCode(e.getCode()));
            throw e;
        } catch (RuntimeException e) {
            recordCounter("music.playback.control.result", "command", command, "outcome", "failed", "code",
                    "PLAYBACK_CONTROL_FAILED");
            logControlResult(normalizedActor, command, "failed", "PLAYBACK_CONTROL_FAILED");
            throw new BusinessException("PLAYBACK_CONTROL_FAILED", "播放控制执行失败", "请稍后重试");
        } finally {
            recordDuration("music.playback.control.latency", System.nanoTime() - startedAtNanos);
        }
    }

    public NowPlayingStatusResponse getNowPlaying(String actor) {
        PlaybackStateSnapshot snapshot = stateByActor.get(normalizeActor(actor));
        return toResponse(snapshot, null);
    }

    public void markTrackStarted(String actor, Long trackId) {
        if (trackId == null) {
            return;
        }
        String normalizedActor = normalizeActor(actor);
        stateByActor.compute(normalizedActor, (key, current) -> {
            PlaybackStateSnapshot working = current == null
                    ? PlaybackStateSnapshot.ready()
                    : current.mutableCopy();
            PlaybackStatus previousState = working.state;
            Long previousTrackId = working.currentTrackId;

            List<Long> queue = new ArrayList<>();
            if (working.queueTrackIds != null) {
                queue.addAll(working.queueTrackIds);
            }
            if (!queue.contains(trackId)) {
                queue.clear();
                queue.add(trackId);
            }

            working.queueTrackIds = queue;
            working.currentTrackId = trackId;
            working.progressSec = 0;
            working.state = PlaybackStatus.PLAYING;
            working.updatedAtEpochSecond = nowEpochSeconds();

            if (previousTrackId != null && !previousTrackId.equals(trackId)) {
                logTrackSwitch(normalizedActor, "playback_start", previousTrackId, trackId, 0L);
            }
            if (previousState != working.state) {
                logStateTransition(normalizedActor, previousState, working.state, "playback_start");
            }
            return working.freeze();
        });
    }

    private PlaybackStateSnapshot applyCommand(PlaybackStateSnapshot current,
                                               PlaybackControlRequest request,
                                               String command,
                                               String actor) {
        PlaybackStateSnapshot working = current == null
                ? PlaybackStateSnapshot.ready()
                : current.mutableCopy();
        mergeContext(working, request);

        if ("pause".equals(command)) {
            return applyPause(actor, working, command);
        }
        if ("resume".equals(command)) {
            return applyResume(actor, working, command);
        }
        if ("previous".equals(command)) {
            return applyTrackSwitch(actor, working, command, false);
        }
        if ("next".equals(command)) {
            return applyTrackSwitch(actor, working, command, true);
        }
        throw new BusinessException("400", "不支持的播放控制命令", "仅支持 pause/resume/previous/next");
    }

    private void mergeContext(PlaybackStateSnapshot working, PlaybackControlRequest request) {
        if (request == null) {
            return;
        }
        List<Long> queue = sanitizeQueue(request.getQueueTrackIds());
        if (!queue.isEmpty()) {
            working.queueTrackIds = queue;
        }

        Long currentTrackId = request.getCurrentTrackId();
        if (currentTrackId != null) {
            if (!working.queueTrackIds.contains(currentTrackId)) {
                working.queueTrackIds.add(0, currentTrackId);
            }
            working.currentTrackId = currentTrackId;
        } else if (working.currentTrackId == null && !working.queueTrackIds.isEmpty()) {
            working.currentTrackId = working.queueTrackIds.get(0);
        }

        if (working.currentTrackId != null && !working.queueTrackIds.contains(working.currentTrackId)) {
            working.queueTrackIds.add(0, working.currentTrackId);
        }

        if (request.getProgressSec() != null) {
            working.progressSec = normalizeProgress(request.getProgressSec());
        }
    }

    private PlaybackStateSnapshot applyPause(String actor, PlaybackStateSnapshot working, String command) {
        ensureTrackSelected(working.currentTrackId);
        PlaybackStatus previousState = working.state;
        working.state = PlaybackStatus.PAUSED;
        working.updatedAtEpochSecond = nowEpochSeconds();
        if (previousState != working.state) {
            logStateTransition(actor, previousState, working.state, command);
        }
        return working.freeze();
    }

    private PlaybackStateSnapshot applyResume(String actor, PlaybackStateSnapshot working, String command) {
        ensureTrackSelected(working.currentTrackId);
        ensureTrackExists(working.currentTrackId);
        PlaybackStatus previousState = working.state;
        working.state = PlaybackStatus.PLAYING;
        working.updatedAtEpochSecond = nowEpochSeconds();
        if (previousState != working.state) {
            logStateTransition(actor, previousState, working.state, command);
        }
        return working.freeze();
    }

    private PlaybackStateSnapshot applyTrackSwitch(String actor,
                                                   PlaybackStateSnapshot working,
                                                   String command,
                                                   boolean forward) {
        ensureTrackSelected(working.currentTrackId);
        List<Long> queue = working.queueTrackIds == null ? new ArrayList<Long>() : new ArrayList<>(working.queueTrackIds);
        if (queue.isEmpty()) {
            queue.add(working.currentTrackId);
        }
        int currentIndex = queue.indexOf(working.currentTrackId);
        if (currentIndex < 0) {
            queue.add(0, working.currentTrackId);
            currentIndex = 0;
        }
        int targetIndex = forward ? currentIndex + 1 : currentIndex - 1;
        if (targetIndex < 0 || targetIndex >= queue.size()) {
            throw new BusinessException("PLAYBACK_QUEUE_BOUNDARY", "已到队列边界", "请选择其他歌曲播放");
        }

        Long previousTrackId = working.currentTrackId;
        Long targetTrackId = queue.get(targetIndex);
        ensureTrackExists(targetTrackId);

        long switchStartNanos = System.nanoTime();
        PlaybackStatus beforeBuffering = working.state;
        working.state = PlaybackStatus.BUFFERING;
        if (beforeBuffering != working.state) {
            logStateTransition(actor, beforeBuffering, working.state, command);
        }

        working.currentTrackId = targetTrackId;
        working.queueTrackIds = queue;
        working.progressSec = 0;

        PlaybackStatus beforePlaying = working.state;
        working.state = PlaybackStatus.PLAYING;
        if (beforePlaying != working.state) {
            logStateTransition(actor, beforePlaying, working.state, command);
        }
        working.updatedAtEpochSecond = nowEpochSeconds();

        long switchCostNanos = System.nanoTime() - switchStartNanos;
        recordDuration("music.playback.track.switch.latency", switchCostNanos);
        logTrackSwitch(actor, command, previousTrackId, targetTrackId, switchCostNanos);
        return working.freeze();
    }

    private NowPlayingStatusResponse toResponse(PlaybackStateSnapshot snapshot, String lastCommand) {
        PlaybackStateSnapshot safeSnapshot = snapshot == null ? PlaybackStateSnapshot.ready() : snapshot;
        Long currentTrackId = safeSnapshot.currentTrackId;
        List<Long> queue = safeSnapshot.queueTrackIds == null
                ? new ArrayList<Long>()
                : new ArrayList<>(safeSnapshot.queueTrackIds);
        int currentIndex = currentTrackId == null ? -1 : queue.indexOf(currentTrackId);
        boolean hasPrevious = currentIndex > 0;
        boolean hasNext = currentIndex >= 0 && currentIndex < queue.size() - 1;

        NowPlayingTrackResponse trackResponse = null;
        if (currentTrackId != null) {
            TrackEntity track = trackMapper.selectById(currentTrackId);
            if (track != null) {
                trackResponse = new NowPlayingTrackResponse(
                        track.getId(),
                        track.getTitle(),
                        track.getArtist(),
                        track.getAlbum(),
                        track.getDurationSec(),
                        safeSnapshot.progressSec
                );
            }
        }

        return new NowPlayingStatusResponse(
                safeSnapshot.state.value,
                lastCommand,
                currentTrackId,
                trackResponse,
                queue,
                hasPrevious,
                hasNext,
                safeSnapshot.progressSec,
                safeSnapshot.updatedAtEpochSecond
        );
    }

    private String normalizeCommand(String command) {
        if (!StringUtils.hasText(command)) {
            throw new BusinessException("400", "控制命令不能为空", "请重试播放控制");
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if ("pause".equals(normalized)
                || "resume".equals(normalized)
                || "previous".equals(normalized)
                || "next".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException("400", "不支持的播放控制命令", "仅支持 pause/resume/previous/next");
    }

    private int normalizeProgress(Integer progressSec) {
        if (progressSec == null || progressSec < 0) {
            return 0;
        }
        return progressSec;
    }

    private List<Long> sanitizeQueue(List<Long> queueTrackIds) {
        if (queueTrackIds == null || queueTrackIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> deduped = new LinkedHashSet<>();
        for (Long trackId : queueTrackIds) {
            if (trackId != null && trackId > 0) {
                deduped.add(trackId);
            }
        }
        return new ArrayList<>(deduped);
    }

    private void ensureTrackSelected(Long trackId) {
        if (trackId == null) {
            throw new BusinessException("PLAYBACK_NO_TRACK", "当前无可控制的播放曲目", "请先开始播放后重试");
        }
    }

    private void ensureTrackExists(Long trackId) {
        if (trackMapper.selectById(trackId) == null) {
            throw new BusinessException("404", "歌曲不存在", "请刷新后重试");
        }
    }

    private String normalizeActor(String actor) {
        if (!StringUtils.hasText(actor)) {
            return "anonymous";
        }
        String trimmed = actor.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private String safeCode(String code) {
        return StringUtils.hasText(code) ? code : "UNKNOWN";
    }

    private String currentTraceId() {
        String traceId = MDC.get("requestId");
        return StringUtils.hasText(traceId) ? traceId : "unknown";
    }

    private long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private void logControlClick(String actor, String command, PlaybackControlRequest request) {
        int queueSize = request == null || request.getQueueTrackIds() == null ? 0 : request.getQueueTrackIds().size();
        log.info("PLAYBACK_EVENT event=control_click actor={} command={} currentTrackId={} queueSize={} traceId={}",
                actor,
                command,
                request == null ? null : request.getCurrentTrackId(),
                queueSize,
                currentTraceId());
    }

    private void logControlResult(String actor, String command, String outcome, String reasonCode) {
        if (StringUtils.hasText(reasonCode)) {
            log.warn("PLAYBACK_EVENT event=control_result actor={} command={} outcome={} code={} traceId={}",
                    actor, command, outcome, reasonCode, currentTraceId());
            return;
        }
        log.info("PLAYBACK_EVENT event=control_result actor={} command={} outcome={} traceId={}",
                actor, command, outcome, currentTraceId());
    }

    private void logTrackSwitch(String actor,
                                String command,
                                Long fromTrackId,
                                Long toTrackId,
                                long latencyNanos) {
        log.info("PLAYBACK_EVENT event=track_switch actor={} command={} fromTrackId={} toTrackId={} latencyMs={} traceId={}",
                actor,
                command,
                fromTrackId,
                toTrackId,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, latencyNanos)),
                currentTraceId());
    }

    private void logStateTransition(String actor, PlaybackStatus from, PlaybackStatus to, String reason) {
        log.info("PLAYBACK_EVENT event=state_transition actor={} from={} to={} reason={} traceId={}",
                actor, from.value, to.value, reason, currentTraceId());
        recordCounter("music.playback.state.transition", "from", from.value, "to", to.value, "reason", reason);
    }

    private void recordCounter(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (Exception ex) {
            log.debug("Playback metric counter failed, name={}", name, ex);
        }
    }

    private void recordDuration(String name, long nanos) {
        if (meterRegistry == null || nanos <= 0) {
            return;
        }
        try {
            meterRegistry.timer(name).record(nanos, TimeUnit.NANOSECONDS);
        } catch (Exception ex) {
            log.debug("Playback metric timer failed, name={}", name, ex);
        }
    }

    private enum PlaybackStatus {
        READY("ready"),
        PLAYING("playing"),
        PAUSED("paused"),
        BUFFERING("buffering"),
        ERROR("error"),
        RECOVERING("recovering");

        private final String value;

        PlaybackStatus(String value) {
            this.value = value;
        }
    }

    private static class PlaybackStateSnapshot {

        private PlaybackStatus state = PlaybackStatus.READY;
        private Long currentTrackId;
        private List<Long> queueTrackIds = new ArrayList<>();
        private int progressSec;
        private long updatedAtEpochSecond = System.currentTimeMillis() / 1000L;

        static PlaybackStateSnapshot ready() {
            PlaybackStateSnapshot snapshot = new PlaybackStateSnapshot();
            snapshot.state = PlaybackStatus.READY;
            snapshot.currentTrackId = null;
            snapshot.queueTrackIds = new ArrayList<>();
            snapshot.progressSec = 0;
            snapshot.updatedAtEpochSecond = System.currentTimeMillis() / 1000L;
            return snapshot;
        }

        PlaybackStateSnapshot mutableCopy() {
            PlaybackStateSnapshot copy = new PlaybackStateSnapshot();
            copy.state = this.state;
            copy.currentTrackId = this.currentTrackId;
            copy.queueTrackIds = this.queueTrackIds == null
                    ? new ArrayList<Long>()
                    : new ArrayList<>(this.queueTrackIds);
            copy.progressSec = this.progressSec;
            copy.updatedAtEpochSecond = this.updatedAtEpochSecond;
            return copy;
        }

        PlaybackStateSnapshot freeze() {
            PlaybackStateSnapshot frozen = mutableCopy();
            frozen.queueTrackIds = frozen.queueTrackIds == null
                    ? Collections.<Long>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(frozen.queueTrackIds));
            return frozen;
        }
    }
}
