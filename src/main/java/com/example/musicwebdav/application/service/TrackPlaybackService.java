package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.PlaybackSessionResponse;
import com.example.musicwebdav.common.config.AppPlaybackProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.common.util.PlaybackSignUtil;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

@Service
public class TrackPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(TrackPlaybackService.class);

    private final TrackMapper trackMapper;
    private final WebDavConfigMapper webDavConfigMapper;
    private final WebDavClient webDavClient;
    private final AppSecurityProperties appSecurityProperties;
    private final PlaybackTokenService playbackTokenService;
    private final PlaybackControlService playbackControlService;
    private final AppPlaybackProperties appPlaybackProperties;
    private final MeterRegistry meterRegistry;

    public TrackPlaybackService(TrackMapper trackMapper,
                                WebDavConfigMapper webDavConfigMapper,
                                WebDavClient webDavClient,
                                AppSecurityProperties appSecurityProperties,
                                PlaybackTokenService playbackTokenService,
                                PlaybackControlService playbackControlService,
                                AppPlaybackProperties appPlaybackProperties,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.trackMapper = trackMapper;
        this.webDavConfigMapper = webDavConfigMapper;
        this.webDavClient = webDavClient;
        this.appSecurityProperties = appSecurityProperties;
        this.playbackTokenService = playbackTokenService;
        this.playbackControlService = playbackControlService;
        this.appPlaybackProperties = appPlaybackProperties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public PlaybackSessionResponse createPlaybackSession(Long trackId, String actor) {
        long startedAtNanos = System.nanoTime();
        try {
            TrackEntity track = trackMapper.selectById(trackId);
            if (track == null) {
                throw new BusinessException("404", "歌曲不存在", "请刷新后重试");
            }
            PlaybackTokenService.PlaybackTokenIssue tokenIssue =
                    playbackTokenService.issueTrackStreamToken(actor, trackId);
            try {
                playbackControlService.markTrackStarted(actor, trackId);
            } catch (Exception ex) {
                log.warn("PLAYBACK_STATE_INIT_FAILED actor={} trackId={} traceId={}",
                        safeActor(actor), trackId, currentTraceId(), ex);
            }
            String signedPath = "/api/v1/tracks/" + trackId + "/stream?playbackToken="
                    + UriUtils.encodeQueryParam(tokenIssue.getToken(), StandardCharsets.UTF_8.name());

            recordPlaybackMetric("music.playback.sign.success", 1, "outcome", "success");
            logPlaybackAudit("success", actor, trackId, startedAtNanos, null);
            return new PlaybackSessionResponse(
                    trackId,
                    signedPath,
                    tokenIssue.getIssuedAtEpochSecond(),
                    tokenIssue.getExpiresAtEpochSecond(),
                    Math.max(1L, appPlaybackProperties.getRefreshBeforeExpirySeconds())
            );
        } catch (BusinessException e) {
            recordPlaybackMetric("music.playback.sign.failed", 1, "code", safeCode(e.getCode()));
            logPlaybackAudit("failed", actor, trackId, startedAtNanos, safeCode(e.getCode()));
            throw e;
        } catch (Exception e) {
            recordPlaybackMetric("music.playback.sign.failed", 1, "code", "PLAYBACK_SIGNING_FAILED");
            logPlaybackAudit("failed", actor, trackId, startedAtNanos, "PLAYBACK_SIGNING_FAILED");
            throw new BusinessException("PLAYBACK_SIGNING_FAILED", "播放签名服务暂不可用", "请稍后重试");
        } finally {
            recordDuration("music.playback.sign.latency", System.nanoTime() - startedAtNanos);
        }
    }

    public void redirectToProxy(Long trackId,
                                String playbackToken,
                                String backendBaseUrl,
                                HttpServletResponse response) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }
        String normalizedBaseUrl = backendBaseUrl == null ? "" : backendBaseUrl.trim();
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        String fileUrl = normalizedBaseUrl + "/api/v1/tracks/" + trackId + "/stream-proxy";
        if (StringUtils.hasText(playbackToken)) {
            fileUrl = fileUrl + "?playbackToken="
                    + UriUtils.encodeQueryParam(playbackToken, StandardCharsets.UTF_8.name());
        }
        try {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(fileUrl);
        } catch (IOException e) {
            throw new BusinessException("500", "播放重定向失败：" + e.getMessage());
        }
    }

    public void proxyTrackStream(Long trackId, String rangeHeader, HttpServletResponse response) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }
        if (track.getSourceConfigId() == null) {
            throw new BusinessException("500", "歌曲来源配置缺失");
        }

        WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
        if (config == null) {
            throw new BusinessException("404", "歌曲来源配置不存在");
        }

        String streamUrl = buildFileUrl(config, track.getSourcePath());
        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
        proxyTrackStreamWithRange(trackId, track.getSourcePath(), track.getMimeType(),
                config.getUsername(), decryptedPassword, streamUrl, rangeHeader, response,
                "PLAYBACK_STREAM_PROXY_FAILED", "音频流读取失败");
    }

    /**
     * Signed stream endpoint with Range support.
     * Verifies HMAC signature, then proxies the audio from WebDAV with full
     * HTTP Range support (for seeking / progress bar). No Bearer token needed.
     */
    public void proxyTrackStreamSigned(Long trackId, long expire, String signature,
                                       String rangeHeader, HttpServletResponse response) {
        // 1. Verify signature
        if (!PlaybackSignUtil.verify(appSecurityProperties.getPlaybackSignKey(),
                trackId, expire, signature)) {
            throw new BusinessException("403", "签名无效或已过期");
        }

        // 2. Load track & config
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null || (track.getIsDeleted() != null && track.getIsDeleted() == 1)) {
            throw new BusinessException("404", "歌曲不存在");
        }
        if (track.getSourceConfigId() == null) {
            throw new BusinessException("500", "歌曲来源配置缺失");
        }

        WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
        if (config == null) {
            throw new BusinessException("404", "歌曲来源配置不存在");
        }

        String streamUrl = buildFileUrl(config, track.getSourcePath());
        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
        proxyTrackStreamWithRange(trackId, track.getSourcePath(), track.getMimeType(),
                config.getUsername(), decryptedPassword, streamUrl, rangeHeader, response,
                "PLAYBACK_SIGNED_STREAM_PROXY_FAILED", "签名音频流读取失败");
    }

    private void proxyTrackStreamWithRange(Long trackId,
                                           String sourcePath,
                                           String mimeTypeRaw,
                                           String username,
                                           String decryptedPassword,
                                           String streamUrl,
                                           String rangeHeader,
                                           HttpServletResponse response,
                                           String errorLogCode,
                                           String errorMessagePrefix) {
        HttpGet httpGet = new HttpGet(streamUrl);
        String basicAuth = Base64.getEncoder().encodeToString(
                (username + ":" + decryptedPassword).getBytes(StandardCharsets.UTF_8));
        httpGet.setHeader("Authorization", "Basic " + basicAuth);
        if (StringUtils.hasText(rangeHeader)) {
            httpGet.setHeader("Range", rangeHeader);
        }

        HttpClient httpClient = HttpClients.createDefault();
        try {
            HttpResponse webDavResponse = httpClient.execute(httpGet);
            int statusCode = webDavResponse.getStatusLine().getStatusCode();
            if (statusCode >= 400) {
                log.error("WebDAV returned error status={} for trackId={}, url={}", statusCode, trackId, streamUrl);
                throw new BusinessException(String.valueOf(statusCode), "WebDAV 音频请求失败，状态码: " + statusCode);
            }

            response.setStatus(statusCode);
            String mimeType = StringUtils.hasText(mimeTypeRaw)
                    ? mimeTypeRaw.trim().toLowerCase(Locale.ROOT)
                    : "application/octet-stream";
            response.setContentType(mimeType);
            copyHeaderIfPresent(webDavResponse, response, "Content-Length");
            copyHeaderIfPresent(webDavResponse, response, "Content-Range");
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "no-store");

            try (InputStream in = webDavResponse.getEntity().getContent();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            }
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.warn("PLAYBACK_STREAM_ABORTED trackId={} sourcePathHash={} traceId={}",
                        trackId, summarizePath(sourcePath), currentTraceId());
                return;
            }
            log.error("{} trackId={} sourcePathHash={} traceId={}",
                    errorLogCode, trackId, summarizePath(sourcePath), currentTraceId(), e);
            throw new BusinessException("500", errorMessagePrefix + "：" + e.getMessage());
        }
    }

    private void copyHeaderIfPresent(HttpResponse source, HttpServletResponse target, String headerName) {
        Header header = source.getFirstHeader(headerName);
        if (header != null) {
            target.setHeader(headerName, header.getValue());
        }
    }

    public void proxyCoverArt(Long trackId, HttpServletResponse response) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }

        if (!StringUtils.hasText(track.getCoverArtUrl())) {
            throw new BusinessException("404", "封面不存在");
        }

        WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
        if (config == null) {
            throw new BusinessException("404", "歌曲来源配置不存在");
        }

        String coverUrl;
        String coverArtUrl = track.getCoverArtUrl();
        String lowerUrl = coverArtUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
            coverUrl = coverArtUrl;
        } else {
            coverUrl = buildFileUrl(config, coverArtUrl);
        }

        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());

        String contentType = guessImageContentType(coverUrl);
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "public, max-age=86400");

        try {
            webDavClient.downloadToOutputStream(config.getUsername(), decryptedPassword,
                    coverUrl, response.getOutputStream());
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.warn("COVER_STREAM_ABORTED trackId={} coverPathHash={} traceId={}",
                        trackId, summarizePath(coverArtUrl), currentTraceId());
                return;
            }
            log.error("COVER_STREAM_FAILED trackId={} coverPathHash={} traceId={}",
                    trackId, summarizePath(coverArtUrl), currentTraceId(), e);
            throw new BusinessException("500", "封面下载失败：" + e.getMessage());
        }
    }

    public String getLyricContent(Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }

        if (track.getHasLyric() == null || track.getHasLyric() != 1
                || !StringUtils.hasText(track.getLyricPath())) {
            throw new BusinessException("404", "歌词不存在");
        }

        WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
        if (config == null) {
            throw new BusinessException("404", "歌曲来源配置不存在");
        }

        String lyricUrl = buildFileUrl(config, track.getLyricPath());
        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            webDavClient.downloadToOutputStream(config.getUsername(), decryptedPassword,
                    lyricUrl, baos);
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            log.error("LYRIC_DOWNLOAD_FAILED trackId={} lyricPathHash={} traceId={}",
                    trackId, summarizePath(track.getLyricPath()), currentTraceId(), e);
            throw new BusinessException("500", "歌词下载失败：" + e.getMessage());
        }
    }

    private String buildFileUrl(WebDavConfigEntity config, String sourcePath) {
        String normalizedPath = normalizeRelativePath(sourcePath);
        if (!StringUtils.hasText(normalizedPath)) {
            throw new BusinessException("500", "歌曲路径为空");
        }
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());
        StringBuilder encodedPath = new StringBuilder();
        for (String segment : normalizedPath.split("/")) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (encodedPath.length() > 0) {
                encodedPath.append('/');
            }
            encodedPath.append(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8.name()));
        }
        String prefix = rootUrl.endsWith("/") ? rootUrl : rootUrl + "/";
        return prefix + encodedPath;
    }

    private String normalizeRelativePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String guessImageContentType(String url) {
        if (url == null) {
            return "image/jpeg";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int queryIdx = lower.indexOf('?');
        if (queryIdx > 0) {
            lower = lower.substring(0, queryIdx);
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    private boolean isClientAbort(Throwable throwable) {
        List<String> signals = new ArrayList<>();
        signals.add("clientabortexception");
        signals.add("broken pipe");
        signals.add("connection reset by peer");
        signals.add("stream is closed");

        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            for (String signal : signals) {
                if (className.contains(signal) || message.contains(signal)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void logPlaybackAudit(String outcome,
                                  String actor,
                                  Long trackId,
                                  long startedAtNanos,
                                  String reasonCode) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        if (reasonCode == null) {
            log.info("PLAYBACK_SIGN_AUDIT actor={} trackId={} outcome={} latencyMs={} traceId={}",
                    safeActor(actor), trackId, outcome, latencyMs, currentTraceId());
        } else {
            log.warn("PLAYBACK_SIGN_AUDIT actor={} trackId={} outcome={} reasonCode={} latencyMs={} traceId={}",
                    safeActor(actor), trackId, outcome, reasonCode, latencyMs, currentTraceId());
        }
    }

    private String safeActor(String actor) {
        if (!StringUtils.hasText(actor)) {
            return "unknown";
        }
        String trimmed = actor.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    private String summarizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "empty";
        }
        String normalized = path.trim().toLowerCase(Locale.ROOT);
        return "len=" + normalized.length() + ",hash=" + Integer.toHexString(normalized.hashCode());
    }

    private String currentTraceId() {
        String traceId = MDC.get("requestId");
        return StringUtils.hasText(traceId) ? traceId : "unknown";
    }

    private String safeCode(String code) {
        return StringUtils.hasText(code) ? code : "UNKNOWN";
    }

    private void recordPlaybackMetric(String name, double value, String... tags) {
        if (meterRegistry == null || value <= 0) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment(value);
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
}
