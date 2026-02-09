package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.request.WebDavTestRequest;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavTestResponse;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
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
