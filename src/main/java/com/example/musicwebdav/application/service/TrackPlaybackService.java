package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.PlaybackSessionResponse;
import com.example.musicwebdav.api.response.CoverSessionResponse;
import com.example.musicwebdav.common.config.AppPlaybackProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.config.AppWebDavProperties;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
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
    private static final int MAX_REDIRECT_HOPS = 5;

    private final TrackMapper trackMapper;
    private final WebDavConfigMapper webDavConfigMapper;
    private final WebDavClient webDavClient;
    private final AppSecurityProperties appSecurityProperties;
    private final PlaybackTokenService playbackTokenService;
    private final PlaybackControlService playbackControlService;
    private final AppPlaybackProperties appPlaybackProperties;
    private final MeterRegistry meterRegistry;
    private final CloseableHttpClient streamHttpClient;

    public TrackPlaybackService(TrackMapper trackMapper,
                                WebDavConfigMapper webDavConfigMapper,
                                WebDavClient webDavClient,
                                AppSecurityProperties appSecurityProperties,
                                PlaybackTokenService playbackTokenService,
                                PlaybackControlService playbackControlService,
                                AppPlaybackProperties appPlaybackProperties,
                                AppWebDavProperties appWebDavProperties,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.trackMapper = trackMapper;
        this.webDavConfigMapper = webDavConfigMapper;
        this.webDavClient = webDavClient;
        this.appSecurityProperties = appSecurityProperties;
        this.playbackTokenService = playbackTokenService;
        this.playbackControlService = playbackControlService;
        this.appPlaybackProperties = appPlaybackProperties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(appWebDavProperties.getConnectTimeoutMs())
                .setSocketTimeout(appWebDavProperties.getSocketTimeoutMs())
                .build();
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(20);
        cm.setDefaultMaxPerRoute(10);
        this.streamHttpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public PlaybackSessionResponse createPlaybackSession(Long trackId, String actor) {
        long startedAtNanos = System.nanoTime();
        try {
            TrackEntity track = trackMapper.selectById(trackId);
            if (track == null) {
                throw new BusinessException("404", "歌曲不存在", "请刷新后重试");
            }
            try {
                playbackControlService.markTrackStarted(actor, trackId);
            } catch (Exception ex) {
                log.warn("PLAYBACK_STATE_INIT_FAILED actor={} trackId={} traceId={}",
                        safeActor(actor), trackId, currentTraceId(), ex);
            }

            // 使用 HMAC 签名的 /stream-signed 端点（支持 Range + Content-Length）
            // 替代旧的 JWT /stream 端点（不支持 Range，iOS AVPlayer 无法获取 duration）
            long nowEpoch = System.currentTimeMillis() / 1000;
            long expireEpoch = nowEpoch + appSecurityProperties.getPlaybackSignTtlSec();
            String signature = PlaybackSignUtil.sign(
                    appSecurityProperties.getPlaybackSignKey(), trackId, expireEpoch);
            String signedPath = "/api/v1/tracks/" + trackId
                    + "/stream-signed?expire=" + expireEpoch + "&sign="
                    + UriUtils.encodeQueryParam(signature, StandardCharsets.UTF_8.name());

            String directStreamUrl = null;
            Map<String, String> directHeaders = null;
            if (track.getSourceConfigId() != null && StringUtils.hasText(track.getSourcePath())) {
                WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
                if (config != null && StringUtils.hasText(config.getUsername())
                        && StringUtils.hasText(config.getPasswordEnc())) {
                    String decryptedPassword = AesCryptoUtil.decrypt(
                            config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
                    String basicAuth = Base64.getEncoder().encodeToString(
                            (config.getUsername() + ":" + decryptedPassword).getBytes(StandardCharsets.UTF_8));
                    directStreamUrl = buildFileUrl(config, track.getSourcePath());
                    directHeaders = new LinkedHashMap<>();
                    directHeaders.put("Authorization", "Basic " + basicAuth);
                }
            }


            recordPlaybackMetric("music.playback.sign.success", 1, "outcome", "success");
            logPlaybackAudit("success", actor, trackId, startedAtNanos, null);
            return new PlaybackSessionResponse(
                    trackId,
                    signedPath,
                    nowEpoch,
                    expireEpoch,
                    Math.max(1L, appPlaybackProperties.getRefreshBeforeExpirySeconds()),
                    StringUtils.hasText(directStreamUrl) ? "DIRECT" : "PROXY",
                    directStreamUrl,
                    directHeaders,
                    signedPath
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

    public CoverSessionResponse createCoverSession(Long trackId) {
        TrackEntity track = trackMapper.selectById(trackId);
        if (track == null) {
            throw new BusinessException("404", "歌曲不存在");
        }
        if (!StringUtils.hasText(track.getCoverArtUrl())) {
            throw new BusinessException("404", "封面不存在");
        }
        if (track.getSourceConfigId() == null) {
            throw new BusinessException("500", "歌曲来源配置缺失");
        }

        WebDavConfigEntity config = webDavConfigMapper.selectById(track.getSourceConfigId());
        if (config == null) {
            throw new BusinessException("404", "歌曲来源配置不存在");
        }
        if (!StringUtils.hasText(config.getUsername()) || !StringUtils.hasText(config.getPasswordEnc())) {
            throw new BusinessException("500", "歌曲来源鉴权信息缺失");
        }

        String coverArtUrl = track.getCoverArtUrl();
        String lowerUrl = coverArtUrl.toLowerCase(Locale.ROOT);
        String coverUrl;
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
            coverUrl = coverArtUrl;
        } else {
            coverUrl = buildFileUrl(config, coverArtUrl);
        }

        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.getUsername() + ":" + decryptedPassword).getBytes(StandardCharsets.UTF_8));

        Map<String, String> directHeaders = new LinkedHashMap<>();
        directHeaders.put("Authorization", "Basic " + basicAuth);

        String fallbackCoverPath = "/api/v1/tracks/" + trackId + "/cover";
        log.info("COVER_SESSION_ISSUED trackId={} mode=DIRECT target={} fallback={} traceId={}",
                trackId, summarizeUrl(coverUrl), fallbackCoverPath, currentTraceId());
        return new CoverSessionResponse(
                trackId,
                "DIRECT",
                coverUrl,
                directHeaders,
                fallbackCoverPath
        );
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
        log.info("PLAYBACK_STREAM_CLIENT_REDIRECT trackId={} location={} hasPlaybackToken={} traceId={}",
                trackId, summarizeUrl(fileUrl), StringUtils.hasText(playbackToken), currentTraceId());
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
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + decryptedPassword).getBytes(StandardCharsets.UTF_8));
        String targetUrl = streamUrl;
        int redirectHops = 0;
        log.info("PLAYBACK_STREAM_PROXY_START trackId={} sourcePathHash={} range={} upstream={} traceId={}",
                trackId, summarizePath(sourcePath), summarizeRange(rangeHeader), summarizeUrl(streamUrl), currentTraceId());
        try {
            HttpResponse webDavResponse = null;
            int statusCode = 0;
            for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
                HttpGet httpGet = new HttpGet(targetUrl);
                httpGet.setHeader("Authorization", basicAuth);
                if (StringUtils.hasText(rangeHeader)) {
                    httpGet.setHeader("Range", rangeHeader);
                }
                webDavResponse = this.streamHttpClient.execute(httpGet);
                statusCode = webDavResponse.getStatusLine().getStatusCode();
                if (!isRedirectStatus(statusCode)) {
                    break;
                }
                Header locationHeader = webDavResponse.getFirstHeader("Location");
                if (locationHeader == null || !StringUtils.hasText(locationHeader.getValue())) {
                    EntityUtils.consumeQuietly(webDavResponse.getEntity());
                    throw new BusinessException("502", "WebDAV 返回重定向但缺少 Location");
                }
                String redirectedUrl = resolveRedirectUrl(targetUrl, locationHeader.getValue());
                redirectHops++;
                log.info("PLAYBACK_STREAM_REDIRECT trackId={} hop={} from={} to={} traceId={}",
                        trackId, hop + 1, targetUrl, redirectedUrl, currentTraceId());
                EntityUtils.consumeQuietly(webDavResponse.getEntity());
                targetUrl = redirectedUrl;
                if (hop == MAX_REDIRECT_HOPS) {
                    throw new BusinessException("502", "WebDAV 重定向次数过多");
                }
            }
            if (webDavResponse == null) {
                throw new BusinessException("500", "WebDAV 音频请求失败：响应为空");
            }
            if (statusCode >= 400) {
                EntityUtils.consumeQuietly(webDavResponse.getEntity());
                log.error("WebDAV returned error status={} for trackId={}, url={}", statusCode, trackId, targetUrl);
                throw new BusinessException(String.valueOf(statusCode), "WebDAV 音频请求失败，状态码: " + statusCode);
            }
            if (isRedirectStatus(statusCode)) {
                EntityUtils.consumeQuietly(webDavResponse.getEntity());
                throw new BusinessException("502", "WebDAV 重定向未收敛");
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
            log.info("PLAYBACK_STREAM_PROXY_UPSTREAM_READY trackId={} status={} hops={} target={} traceId={}",
                    trackId, statusCode, redirectHops, summarizeUrl(targetUrl), currentTraceId());

            try (InputStream in = webDavResponse.getEntity().getContent();
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[65536];
                int len;
                long bytes = 0L;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    bytes += len;
                }
                out.flush();
                log.info("PLAYBACK_STREAM_PROXY_SUCCESS trackId={} status={} hops={} bytes={} traceId={}",
                        trackId, statusCode, redirectHops, bytes, currentTraceId());
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

    private boolean isRedirectStatus(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private String resolveRedirectUrl(String baseUrl, String location) {
        try {
            URI base = new URI(baseUrl);
            return base.resolve(location).toString();
        } catch (URISyntaxException e) {
            throw new BusinessException("502", "WebDAV 返回了非法重定向地址");
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
        String coverMode = (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://"))
                ? "ABSOLUTE_URL"
                : "WEBDAV_PATH";

        String decryptedPassword = AesCryptoUtil.decrypt(
                config.getPasswordEnc(), appSecurityProperties.getEncryptKey());

        String contentType = guessImageContentType(coverUrl);
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "public, max-age=86400");
        log.info("COVER_PROXY_START trackId={} mode={} coverPathHash={} target={} traceId={}",
                trackId, coverMode, summarizePath(coverArtUrl), summarizeUrl(coverUrl), currentTraceId());

        CountingOutputStream countingOutputStream = null;
        try {
            countingOutputStream = new CountingOutputStream(response.getOutputStream());
            webDavClient.downloadToOutputStream(config.getUsername(), decryptedPassword,
                    coverUrl, countingOutputStream);
            log.info("COVER_PROXY_SUCCESS trackId={} mode={} bytes={} contentType={} traceId={}",
                    trackId, coverMode, countingOutputStream.getBytesWritten(), contentType, currentTraceId());
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.warn("COVER_STREAM_ABORTED trackId={} coverPathHash={} mode={} bytes={} traceId={}",
                        trackId, summarizePath(coverArtUrl), coverMode,
                        countingOutputStream == null ? 0L : countingOutputStream.getBytesWritten(),
                        currentTraceId());
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

    private String summarizeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "empty";
        }
        String normalized = url.trim();
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme() == null ? "unknown" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                host = "unknown";
            }
            int port = uri.getPort();
            String portText = port >= 0 ? String.valueOf(port) : "default";
            return "scheme=" + scheme + ",host=" + host + ",port=" + portText
                    + ",pathHash=" + summarizePath(uri.getPath());
        } catch (Exception ignored) {
            return summarizePath(normalized);
        }
    }

    private String summarizeRange(String rangeHeader) {
        if (!StringUtils.hasText(rangeHeader)) {
            return "none";
        }
        return summarizePath(rangeHeader);
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

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long bytesWritten;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
            this.bytesWritten = 0L;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            if (b != null) {
                bytesWritten += b.length;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            if (len > 0) {
                bytesWritten += len;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private long getBytesWritten() {
            return bytesWritten;
        }
    }
}
