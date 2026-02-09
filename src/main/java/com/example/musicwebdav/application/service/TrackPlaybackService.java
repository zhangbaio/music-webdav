package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

@Service
public class TrackPlaybackService {

    private final TrackMapper trackMapper;
    private final WebDavConfigMapper webDavConfigMapper;
    private final WebDavClient webDavClient;

    public TrackPlaybackService(TrackMapper trackMapper,
                                WebDavConfigMapper webDavConfigMapper,
                                WebDavClient webDavClient) {
        this.trackMapper = trackMapper;
        this.webDavConfigMapper = webDavConfigMapper;
        this.webDavClient = webDavClient;
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
}
