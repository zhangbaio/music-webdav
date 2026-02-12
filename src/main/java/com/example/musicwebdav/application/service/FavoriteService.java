package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.FavoriteSyncRequest;
import com.example.musicwebdav.api.response.FavoriteStatusResponse;
import com.example.musicwebdav.api.response.PageResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlaylistTrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteService.class);

    private final PlaylistService playlistService;
    private final PlaylistTrackMapper playlistTrackMapper;
    private final TrackMapper trackMapper;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, FavoriteVersionSnapshot> favoriteVersionByActorTrack = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, FavoriteSyncSnapshot> idempotentResultByActorKey = new ConcurrentHashMap<>();

    public FavoriteService(PlaylistService playlistService,
                           PlaylistTrackMapper playlistTrackMapper,
                           TrackMapper trackMapper,
                           ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.playlistService = playlistService;
        this.playlistTrackMapper = playlistTrackMapper;
        this.trackMapper = trackMapper;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public FavoriteStatusResponse favorite(String actor, Long trackId, String idempotencyKey) {
        return syncFavorite(actor, trackId, true, null, idempotencyKey);
    }

    public FavoriteStatusResponse favorite(Long trackId) {
        return favorite("anonymous", trackId, null);
    }

    public FavoriteStatusResponse syncFavorite(String actor,
                                               Long trackId,
                                               boolean targetFavorite,
                                               Long expectedVersion,
                                               String idempotencyKey) {
        long startedAtNanos = System.nanoTime();
        String safeActor = normalizeActor(actor);
        String syncKey = actorTrackKey(safeActor, trackId);

        log.info("FAVORITE_EVENT event=favorite_toggle_click actor={} trackId={} targetFavorite={} expectedVersion={} traceId={}",
                safeActor, trackId, targetFavorite, expectedVersion, currentTraceId());
        recordCounter("music.favorite.sync.start");
        try {
            if (trackId == null || trackId <= 0) {
                throw new BusinessException("400", "trackId不合法", "请刷新后重试");
            }
            TrackEntity track = trackMapper.selectById(trackId);
            if (track == null) {
                throw new BusinessException("404", "歌曲不存在", "请刷新后重试");
            }

            FavoriteSyncSnapshot idempotent = findIdempotentResult(safeActor, idempotencyKey, trackId, targetFavorite);
            if (idempotent != null) {
                log.info("FAVORITE_EVENT event=sync_resolved actor={} trackId={} mode=idempotent version={} traceId={}",
                        safeActor, trackId, idempotent.response.getVersion(), currentTraceId());
                recordCounter("music.favorite.sync.success", "mode", "idempotent");
                return idempotent.response;
            }

            FavoriteVersionSnapshot current = favoriteVersionByActorTrack.computeIfAbsent(syncKey, key ->
                    loadInitialSnapshot(trackId));
            if (expectedVersion != null && expectedVersion.longValue() != current.version) {
                log.warn("FAVORITE_EVENT event=conflict_detected actor={} trackId={} expectedVersion={} actualVersion={} traceId={}",
                        safeActor, trackId, expectedVersion, current.version, currentTraceId());
                recordCounter("music.favorite.sync.failure", "code", "FAVORITE_CONFLICT");
                throw new BusinessException("FAVORITE_CONFLICT", "收藏状态已被更新", "请刷新当前状态后重试");
            }

            persistTargetFavorite(trackId, targetFavorite);
            long nextVersion = Math.max(1L, current.version + 1L);
            long updatedAtEpochSecond = nowEpochSeconds();
            FavoriteStatusResponse response = new FavoriteStatusResponse(trackId, targetFavorite, updatedAtEpochSecond, nextVersion);
            favoriteVersionByActorTrack.put(syncKey, new FavoriteVersionSnapshot(targetFavorite, nextVersion, updatedAtEpochSecond));
            rememberIdempotentResult(safeActor, idempotencyKey, trackId, targetFavorite, response);
            log.info("FAVORITE_EVENT event=sync_success actor={} trackId={} favorite={} version={} traceId={}",
                    safeActor, trackId, targetFavorite, nextVersion, currentTraceId());
            recordCounter("music.favorite.sync.success", "mode", "persisted");
            return response;
        } catch (BusinessException e) {
            log.warn("FAVORITE_EVENT event=sync_failure actor={} trackId={} code={} traceId={}",
                    safeActor, trackId, safeCode(e.getCode()), currentTraceId());
            recordCounter("music.favorite.sync.failure", "code", safeCode(e.getCode()));
            throw e;
        } finally {
            recordDuration("music.favorite.sync.latency", System.nanoTime() - startedAtNanos);
        }
    }

    public FavoriteStatusResponse syncFavorite(String actor, Long trackId, FavoriteSyncRequest request, String headerIdempotencyKey) {
        if (request == null || request.getTargetFavorite() == null) {
            throw new BusinessException("400", "请求参数不合法", "请检查收藏目标状态后重试");
        }
        String idempotencyKey = StringUtils.hasText(request.getIdempotencyKey())
                ? request.getIdempotencyKey().trim()
                : (StringUtils.hasText(headerIdempotencyKey) ? headerIdempotencyKey.trim() : null);
        return syncFavorite(actor, trackId, request.getTargetFavorite(), request.getExpectedVersion(), idempotencyKey);
    }

    public FavoriteStatusResponse unfavorite(String actor, Long trackId, String idempotencyKey) {
        return syncFavorite(actor, trackId, false, null, idempotencyKey);
    }

    public FavoriteStatusResponse unfavorite(Long trackId) {
        return unfavorite("anonymous", trackId, null);
    }

    public FavoriteStatusResponse status(String actor, Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            return new FavoriteStatusResponse(trackId, false, nowEpochSeconds(), 0L);
        }
        boolean favorite = isFavoritePersisted(trackId);
        FavoriteVersionSnapshot snapshot = favoriteVersionByActorTrack.computeIfAbsent(
                actorTrackKey(normalizeActor(actor), trackId),
                key -> new FavoriteVersionSnapshot(favorite, 0L, nowEpochSeconds())
        );
        if (snapshot.favorite != favorite) {
            long nextVersion = Math.max(1L, snapshot.version + 1L);
            snapshot = new FavoriteVersionSnapshot(favorite, nextVersion, nowEpochSeconds());
            favoriteVersionByActorTrack.put(actorTrackKey(normalizeActor(actor), trackId), snapshot);
            log.info("FAVORITE_EVENT event=conflict_resolved actor={} trackId={} favorite={} version={} traceId={}",
                    normalizeActor(actor), trackId, favorite, snapshot.version, currentTraceId());
        }
        return new FavoriteStatusResponse(trackId, favorite, snapshot.updatedAtEpochSecond, snapshot.version);
    }

    public FavoriteStatusResponse status(Long trackId) {
        return status("anonymous", trackId);
    }

    private void persistTargetFavorite(Long trackId, boolean targetFavorite) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在", "请刷新后重试");
        }
        Long playlistId = playlistService.getOrCreateFavoritesPlaylistId();
        if (targetFavorite) {
            playlistService.addTracks(playlistId, Collections.singletonList(trackId));
        } else {
            playlistService.removeTrack(playlistId, trackId);
        }
    }

    private boolean isFavoritePersisted(Long trackId) {
        Long playlistId = playlistService.getOrCreateFavoritesPlaylistId();
        return playlistTrackMapper.countByPlaylistAndTrack(playlistId, trackId) > 0;
    }

    public PageResponse<TrackResponse> listFavoriteTracks(int pageNo, int pageSize) {
        return playlistService.listFavoriteTracks(pageNo, pageSize);
    }

    private FavoriteVersionSnapshot loadInitialSnapshot(Long trackId) {
        boolean favorite = isFavoritePersisted(trackId);
        return new FavoriteVersionSnapshot(favorite, 0L, nowEpochSeconds());
    }

    private FavoriteSyncSnapshot findIdempotentResult(String actor,
                                                      String idempotencyKey,
                                                      Long trackId,
                                                      boolean targetFavorite) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        FavoriteSyncSnapshot snapshot = idempotentResultByActorKey.get(actor + ":" + idempotencyKey.trim());
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.trackId.equals(trackId) || snapshot.targetFavorite != targetFavorite) {
            throw new BusinessException("FAVORITE_IDEMPOTENCY_CONFLICT", "幂等键已用于其他收藏请求", "请刷新后重试");
        }
        return snapshot;
    }

    private void rememberIdempotentResult(String actor,
                                          String idempotencyKey,
                                          Long trackId,
                                          boolean targetFavorite,
                                          FavoriteStatusResponse response) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        idempotentResultByActorKey.put(
                actor + ":" + idempotencyKey.trim(),
                new FavoriteSyncSnapshot(trackId, targetFavorite, response)
        );
    }

    private String actorTrackKey(String actor, Long trackId) {
        return actor + ":" + trackId;
    }

    private String normalizeActor(String actor) {
        if (!StringUtils.hasText(actor)) {
            return "anonymous";
        }
        String trimmed = actor.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private String currentTraceId() {
        String traceId = MDC.get("requestId");
        return StringUtils.hasText(traceId) ? traceId : "unknown";
    }

    private String safeCode(String code) {
        return StringUtils.hasText(code) ? code : "UNKNOWN";
    }

    private void recordCounter(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (Exception ex) {
            log.debug("Favorite metric counter failed, name={}", name, ex);
        }
    }

    private void recordDuration(String name, long nanos) {
        if (meterRegistry == null || nanos <= 0) {
            return;
        }
        try {
            meterRegistry.timer(name).record(nanos, TimeUnit.NANOSECONDS);
        } catch (Exception ex) {
            log.debug("Favorite metric timer failed, name={}", name, ex);
        }
    }

    private static class FavoriteVersionSnapshot {
        private final boolean favorite;
        private final long version;
        private final long updatedAtEpochSecond;

        private FavoriteVersionSnapshot(boolean favorite, long version, long updatedAtEpochSecond) {
            this.favorite = favorite;
            this.version = version;
            this.updatedAtEpochSecond = updatedAtEpochSecond;
        }
    }

    private static class FavoriteSyncSnapshot {
        private final Long trackId;
        private final boolean targetFavorite;
        private final FavoriteStatusResponse response;

        private FavoriteSyncSnapshot(Long trackId, boolean targetFavorite, FavoriteStatusResponse response) {
            this.trackId = trackId;
            this.targetFavorite = targetFavorite;
            this.response = response;
        }
    }
}
