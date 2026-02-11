package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.request.WebDavTestRequest;
import com.example.musicwebdav.api.response.WebDavDirectoryItemResponse;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavTestResponse;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.domain.model.WebDavDirectoryInfo;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import com.github.sardine.Sardine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class WebDavConnectionService {

    private final WebDavClient webDavClient;
    private final WebDavConfigMapper webDavConfigMapper;
    private final AppSecurityProperties appSecurityProperties;

    public WebDavConnectionService(WebDavClient webDavClient,
                                   WebDavConfigMapper webDavConfigMapper,
                                   AppSecurityProperties appSecurityProperties) {
        this.webDavClient = webDavClient;
        this.webDavConfigMapper = webDavConfigMapper;
        this.appSecurityProperties = appSecurityProperties;
    }

    public WebDavTestResponse testConnection(WebDavTestRequest request) {
        long start = System.currentTimeMillis();
        WebDavConnectResult result = webDavClient.testConnection(
                request.getBaseUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getRootPath());
        return new WebDavTestResponse(result.isSuccess(), result.getMessage(), System.currentTimeMillis() - start);
    }

    public WebDavConfigResponse createConfig(CreateWebDavConfigRequest request) {
        WebDavConnectResult testResult = webDavClient.testConnection(
                request.getBaseUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getRootPath());
        if (!testResult.isSuccess()) {
            throw new BusinessException("WEBDAV_CONNECT_FAILED", testResult.getMessage());
        }

        WebDavConfigEntity entity = new WebDavConfigEntity();
        entity.setName(request.getName());
        entity.setBaseUrl(request.getBaseUrl());
        entity.setUsername(request.getUsername());
        entity.setPasswordEnc(AesCryptoUtil.encrypt(request.getPassword(), appSecurityProperties.getEncryptKey()));
        entity.setRootPath(request.getRootPath());
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);

        try {
            webDavConfigMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("WEBDAV_CONFIG_DUPLICATE", "配置名称已存在，请使用其他名称");
        }

        WebDavConfigEntity saved = webDavConfigMapper.selectById(entity.getId());
        return toResponse(saved);
    }

    public WebDavConfigResponse getConfig(Long id) {
        WebDavConfigEntity entity = webDavConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("404", "WebDAV配置不存在");
        }
        return toResponse(entity);
    }

    public List<WebDavConfigResponse> listConfigs() {
        return webDavConfigMapper.selectAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<WebDavDirectoryItemResponse> listDirectories(Long configId, String path) {
        WebDavConfigEntity config = loadConfig(configId);
        String password = decryptPassword(config.getPasswordEnc());
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());
        String targetUrl = buildDirectoryUrl(rootUrl, normalizeRelativePath(path, false));

        Sardine session = webDavClient.createSession(config.getUsername(), password);
        try {
            WebDavDirectoryInfo info = webDavClient.listDirectory(session, targetUrl, rootUrl);
            List<WebDavDirectoryItemResponse> result = new ArrayList<>();
            for (String subdirectoryUrl : info.getSubdirectoryUrls()) {
                String relativePath = toRelativePath(rootUrl, subdirectoryUrl);
                if (relativePath.isEmpty()) {
                    continue;
                }
                result.add(new WebDavDirectoryItemResponse(extractName(relativePath), relativePath));
            }
            result.sort(Comparator.comparing(WebDavDirectoryItemResponse::getName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (IllegalStateException e) {
            throw new BusinessException("WEBDAV_LIST_FAILED", e.getMessage());
        } finally {
            webDavClient.closeSession(session);
        }
    }

    public void deleteDirectory(Long configId, String path) {
        String normalizedPath = normalizeRelativePath(path, true);
        if (normalizedPath.isEmpty()) {
            throw new BusinessException("400", "不允许删除根目录");
        }

        WebDavConfigEntity config = loadConfig(configId);
        String password = decryptPassword(config.getPasswordEnc());
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());
        String targetUrl = buildDirectoryUrl(rootUrl, normalizedPath);
        try {
            webDavClient.delete(config.getUsername(), password, targetUrl);
        } catch (IllegalStateException e) {
            throw new BusinessException("WEBDAV_DELETE_FAILED", e.getMessage());
        }
    }

    private WebDavConfigEntity loadConfig(Long configId) {
        WebDavConfigEntity config = webDavConfigMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("404", "WebDAV配置不存在");
        }
        return config;
    }

    private String decryptPassword(String passwordEnc) {
        return AesCryptoUtil.decrypt(passwordEnc, appSecurityProperties.getEncryptKey());
    }

    private String normalizeRelativePath(String path, boolean required) {
        if (path == null || path.trim().isEmpty()) {
            if (required) {
                throw new BusinessException("400", "目录路径不能为空");
            }
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException("400", "目录路径不合法");
            }
        }
        return normalized;
    }

    private String buildDirectoryUrl(String rootUrl, String relativePath) {
        String normalizedRoot = rootUrl.endsWith("/") ? rootUrl : rootUrl + "/";
        if (relativePath == null || relativePath.isEmpty()) {
            return normalizedRoot;
        }
        StringBuilder builder = new StringBuilder(normalizedRoot);
        for (String segment : relativePath.split("/")) {
            builder.append(encodePathSegment(segment)).append('/');
        }
        return builder.toString();
    }

    private String encodePathSegment(String segment) {
        try {
            String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8.name());
            return encoded.replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException("路径编码失败", e);
        }
    }

    private String toRelativePath(String rootUrl, String directoryUrl) {
        URI rootUri = URI.create(rootUrl.endsWith("/") ? rootUrl : rootUrl + "/");
        URI directoryUri = URI.create(directoryUrl.endsWith("/") ? directoryUrl : directoryUrl + "/");
        String rootPath = rootUri.getPath();
        String targetPath = directoryUri.getPath();
        String relativePath = targetPath.startsWith(rootPath)
                ? targetPath.substring(rootPath.length())
                : targetPath;
        while (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        while (relativePath.endsWith("/")) {
            relativePath = relativePath.substring(0, relativePath.length() - 1);
        }
        if (relativePath.isEmpty()) {
            return relativePath;
        }
        try {
            return URLDecoder.decode(relativePath, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalStateException("路径解码失败", e);
        }
    }

    private String extractName(String relativePath) {
        int idx = relativePath.lastIndexOf('/');
        if (idx < 0 || idx == relativePath.length() - 1) {
            return relativePath;
        }
        return relativePath.substring(idx + 1);
    }

    private WebDavConfigResponse toResponse(WebDavConfigEntity entity) {
        return new WebDavConfigResponse(
                entity.getId(),
                entity.getName(),
                entity.getBaseUrl(),
                entity.getUsername(),
                entity.getRootPath(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
