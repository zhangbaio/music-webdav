package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.common.util.PlaybackSignUtil;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TrackPlaybackService(TrackMapper trackMapper,
                                WebDavConfigMapper webDavConfigMapper,
                                WebDavClient webDavClient,
                                AppSecurityProperties appSecurityProperties) {
        this.trackMapper = trackMapper;
        this.webDavConfigMapper = webDavConfigMapper;
        this.webDavClient = webDavClient;
        this.appSecurityProperties = appSecurityProperties;
    }

    public void redirectToProxy(Long trackId,
                                String requestToken,
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
        if (StringUtils.hasText(requestToken)) {
            fileUrl = fileUrl + "?token=" + UriUtils.encodeQueryParam(requestToken, StandardCharsets.UTF_8.name());
        }
        try {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(fileUrl);
        } catch (IOException e) {
            throw new BusinessException("500", "播放重定向失败：" + e.getMessage());
        }
    }

    public void proxyTrackStream(Long trackId, HttpServletResponse response) {
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

        String mimeType = StringUtils.hasText(track.getMimeType())
                ? track.getMimeType().trim().toLowerCase(Locale.ROOT)
                : "application/octet-stream";
        response.setContentType(mimeType);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Accept-Ranges", "none");

        try {
            webDavClient.downloadToOutputStream(
                    config.getUsername(), decryptedPassword, streamUrl, response.getOutputStream());
        } catch (IOException e) {
            if (isClientAbort(e)) {
                log.warn("Client aborted audio stream, trackId={}, streamUrl={}", trackId, streamUrl);
                return;
            }
            log.error("Failed to proxy audio stream for trackId={}, streamUrl={}", trackId, streamUrl, e);
            throw new BusinessException("500", "音频流读取失败：" + e.getMessage());
        }
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

        // 3. Build WebDAV GET request with Basic Auth and optional Range
        HttpGet httpGet = new HttpGet(streamUrl);
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.getUsername() + ":" + decryptedPassword).getBytes(StandardCharsets.UTF_8));
        httpGet.setHeader("Authorization", "Basic " + basicAuth);

        if (StringUtils.hasText(rangeHeader)) {
            httpGet.setHeader("Range", rangeHeader);
        }

        // 4. Execute and stream-forward response
        HttpClient httpClient = HttpClients.createDefault();
        try {
            HttpResponse webDavResponse = httpClient.execute(httpGet);
            int statusCode = webDavResponse.getStatusLine().getStatusCode();

            if (statusCode >= 400) {
                log.error("WebDAV returned error status={} for trackId={}, url={}", statusCode, trackId, streamUrl);
                throw new BusinessException(String.valueOf(statusCode), "WebDAV 音频请求失败，状态码: " + statusCode);
            }

            // Forward status: 200 (full) or 206 (partial)
            response.setStatus(statusCode);

            // Forward content type
            String mimeType = StringUtils.hasText(track.getMimeType())
                    ? track.getMimeType().trim().toLowerCase(Locale.ROOT)
                    : "application/octet-stream";
            response.setContentType(mimeType);

            // Forward essential headers from WebDAV response
            copyHeaderIfPresent(webDavResponse, response, "Content-Length");
            copyHeaderIfPresent(webDavResponse, response, "Content-Range");
            response.setHeader("Accept-Ranges", "bytes");
            response.setHeader("Cache-Control", "no-store");

            // 5. Stream-forward bytes (8KB buffer, no full buffering)
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
                log.warn("Client aborted signed stream, trackId={}", trackId);
                return;
            }
            log.error("Failed to proxy signed stream for trackId={}, url={}", trackId, streamUrl, e);
            throw new BusinessException("500", "签名音频流读取失败：" + e.getMessage());
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
                log.warn("Client aborted cover stream, trackId={}, coverUrl={}", trackId, coverUrl);
                return;
            }
            log.error("Failed to proxy cover art for trackId={}, coverUrl={}", trackId, coverUrl, e);
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
            log.error("Failed to download lyric for trackId={}, lyricUrl={}", trackId, lyricUrl, e);
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
}
