package com.example.musicwebdav.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
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
        WebDavConfigEntity config = new WebDavConfigEntity();
        config.setId(1L);
        config.setBaseUrl("https://dav.example.com");
        config.setUsername("alice");
        config.setPasswordEnc(AesCryptoUtil.encrypt("secret-pass", "1234567890abcdef"));
        config.setRootPath("/music");
        config.setEnabled(1);

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
    void testSavedConnectionShouldFailWhenNoConfigPresent() {
        when(webDavConfigMapper.selectFirstEnabled()).thenReturn(null);

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> webDavConnectionService.testSavedConnection(null));

        assertEquals("WEBDAV_CONFIG_NOT_FOUND", ex.getCode());
    }
}
