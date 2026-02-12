package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.request.AdminWebDavRecoveryRequest;
import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavRecoveryStatusResponse;
import com.example.musicwebdav.api.response.WebDavTestResponse;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.domain.model.WebDavConnectResult;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebDavConnectionServiceTest {

    private WebDavClient webDavClient;
    private WebDavConfigMapper webDavConfigMapper;
    private WebDavConnectionService webDavConnectionService;

    @BeforeEach
    void setUp() {
        webDavClient = mock(WebDavClient.class);
        webDavConfigMapper = mock(WebDavConfigMapper.class);

        AppSecurityProperties securityProperties = new AppSecurityProperties();
        securityProperties.setEncryptKey("1234567890abcdef");

        webDavConnectionService = new WebDavConnectionService(webDavClient, webDavConfigMapper, securityProperties);
    }

    @Test
    void createConfigShouldEncryptPasswordAndReturnMaskedResponse() {
        CreateWebDavConfigRequest request = new CreateWebDavConfigRequest();
        request.setName("main-config");
        request.setBaseUrl("https://dav.example.com");
        request.setUsername("alice");
        request.setPassword("secret-pass");
        request.setRootPath(null);
        request.setEnabled(true);

        when(webDavClient.testConnection(
                eq("https://dav.example.com"),
                eq("alice"),
                eq("secret-pass"),
                eq("/")))
                .thenReturn(WebDavConnectResult.success("连接成功"));

        AtomicReference<WebDavConfigEntity> insertedRef = new AtomicReference<>();
        doAnswer(invocation -> {
            WebDavConfigEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            insertedRef.set(entity);
            return 1;
        }).when(webDavConfigMapper).insert(any(WebDavConfigEntity.class));

        when(webDavConfigMapper.selectById(100L)).thenAnswer(invocation -> insertedRef.get());

        WebDavConfigResponse response = webDavConnectionService.createConfig(request);
        WebDavConfigEntity inserted = insertedRef.get();

        assertEquals("/", inserted.getRootPath());
        assertNotEquals("secret-pass", inserted.getPasswordEnc());
        assertEquals("secret-pass", AesCryptoUtil.decrypt(inserted.getPasswordEnc(), "1234567890abcdef"));

        assertEquals(100L, response.getId());
        assertEquals("alice", response.getUsername());
        assertEquals("/", response.getRootPath());
    }

    @Test
    void createConfigShouldReturnBusinessErrorWhenConnectionCheckFailed() {
        CreateWebDavConfigRequest request = new CreateWebDavConfigRequest();
        request.setName("main-config");
        request.setBaseUrl("https://dav.example.com");
        request.setUsername("alice");
        request.setPassword("wrong-pass");

        when(webDavClient.testConnection(any(String.class), any(String.class), any(String.class), any(String.class)))
                .thenReturn(WebDavConnectResult.failure(
                        "WEBDAV_AUTH_FAILED",
                        "WebDAV鉴权失败",
                        "请联系管理员检查用户名或密码",
                        "NO_PERMISSION"));

        BusinessException ex = assertThrows(BusinessException.class, () -> webDavConnectionService.createConfig(request));
        assertEquals("WEBDAV_AUTH_FAILED", ex.getCode());
        assertEquals("请联系管理员检查用户名或密码", ex.getUserAction());
    }

    @Test
    void testSavedConnectionShouldUseEnabledConfigWhenIdNotProvided() {
        WebDavConfigEntity config = buildConfig(1L, "alice", "secret-pass", "/music");

        when(webDavConfigMapper.selectFirstEnabled()).thenReturn(config);
        when(webDavClient.testConnection(
                eq("https://dav.example.com"),
                eq("alice"),
                eq("secret-pass"),
                eq("/music")))
                .thenReturn(WebDavConnectResult.success("连接成功"));

        WebDavTestResponse response = webDavConnectionService.testSavedConnection(null);

        assertEquals("SUCCESS", response.getStatus());
        assertEquals("ACCESSIBLE", response.getDirectoryAccess());
        assertEquals("WEBDAV_TEST_OK", response.getCode());
        assertTrue(response.getLatencyMs() >= 0);
    }

    @Test
    void testSavedConnectionAuthFailureShouldMarkNeedsReauthStatus() {
        WebDavConfigEntity config = buildConfig(2L, "alice", "secret-pass", "/music");

        when(webDavConfigMapper.selectFirstEnabled()).thenReturn(config);
        when(webDavConfigMapper.selectById(2L)).thenReturn(config);
        when(webDavClient.testConnection(any(String.class), any(String.class), any(String.class), any(String.class)))
                .thenReturn(WebDavConnectResult.failure(
                        "WEBDAV_AUTH_FAILED",
                        "WebDAV鉴权失败",
                        "请联系管理员检查用户名或密码",
                        "NO_PERMISSION"));

        WebDavTestResponse response = webDavConnectionService.testSavedConnection(null);
        WebDavRecoveryStatusResponse recoveryStatus = webDavConnectionService.getRecoveryStatus(2L);

        assertEquals("FAILED", response.getStatus());
        assertEquals("needs_reauth", recoveryStatus.getStatus());
        assertEquals("WEBDAV_AUTH_FAILED", recoveryStatus.getCode());
    }

    @Test
    void adminRecoverShouldPatchConfigAndReturnHealthyStatus() {
        WebDavConfigEntity config = buildConfig(3L, "old-user", "old-pass", "/old");
        when(webDavConfigMapper.selectById(3L)).thenReturn(config);
        when(webDavClient.testConnection(
                eq("https://dav.example.com"),
                eq("new-user"),
                eq("new-pass"),
                eq("/new")))
                .thenReturn(WebDavConnectResult.success("连接成功"));

        AdminWebDavRecoveryRequest request = new AdminWebDavRecoveryRequest();
        request.setConfigId(3L);
        request.setUsername("new-user");
        request.setPassword("new-pass");
        request.setRootPath("/new");

        WebDavRecoveryStatusResponse response = webDavConnectionService.adminRecover(request, "api-client");

        assertEquals("healthy", response.getStatus());
        assertEquals("WEBDAV_TEST_OK", response.getCode());
        assertEquals("new-user", config.getUsername());
        assertEquals("/new", config.getRootPath());
        assertEquals("new-pass", AesCryptoUtil.decrypt(config.getPasswordEnc(), "1234567890abcdef"));
        verify(webDavConfigMapper).updateById(config);
    }

    @Test
    void testSavedConnectionShouldFailWhenNoConfigPresent() {
        when(webDavConfigMapper.selectFirstEnabled()).thenReturn(null);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> webDavConnectionService.testSavedConnection(null));

        assertEquals("WEBDAV_CONFIG_NOT_FOUND", ex.getCode());
    }

    private WebDavConfigEntity buildConfig(Long id, String username, String password, String rootPath) {
        WebDavConfigEntity config = new WebDavConfigEntity();
        config.setId(id);
        config.setBaseUrl("https://dav.example.com");
        config.setUsername(username);
        config.setPasswordEnc(AesCryptoUtil.encrypt(password, "1234567890abcdef"));
        config.setRootPath(rootPath);
        config.setEnabled(1);
        return config;
    }
}
