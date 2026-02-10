package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.servlet.http.HttpServletResponse;
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

    public void redirectToWebDav(Long trackId, HttpServletResponse response) {
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

        String fileUrl = buildFileUrl(config, track.getSourcePath());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", fileUrl);
        response.setHeader("Cache-Control", "no-store");
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
            log.error("Failed to proxy audio stream for trackId={}, streamUrl={}", trackId, streamUrl, e);
            throw new BusinessException("500", "音频流读取失败：" + e.getMessage());
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
}
