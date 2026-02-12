package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.AdminWebDavRecoveryRequest;
import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.request.WebDavTestRequest;
import com.example.musicwebdav.api.response.WebDavDirectoryItemResponse;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavRecoveryStatusResponse;
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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class WebDavConnectionService {

    private static final Logger log = LoggerFactory.getLogger(WebDavConnectionService.class);

    private static final String RECOVERY_NEEDS_REAUTH = "needs_reauth";
    private static final String RECOVERY_RECOVERING = "recovering";
    private static final String RECOVERY_HEALTHY = "healthy";
    private static final String RECOVERY_FAILED = "failed";

    private static final Set<String> AUTH_RECOVERY_CODES = new HashSet<String>();

    static {
        AUTH_RECOVERY_CODES.add("WEBDAV_AUTH_FAILED");
        AUTH_RECOVERY_CODES.add("WEBDAV_PERMISSION_DENIED");
    }

    private final WebDavClient webDavClient;
    private final WebDavConfigMapper webDavConfigMapper;
    private final AppSecurityProperties appSecurityProperties;
    private final ConcurrentMap<Long, RecoveryState> recoveryStateByConfig = new ConcurrentHashMap<Long, RecoveryState>();

    public WebDavConnectionService(WebDavClient webDavClient,
                                   WebDavConfigMapper webDavConfigMapper,
                                   AppSecurityProperties appSecurityProperties) {
        this.webDavClient = webDavClient;
        this.webDavConfigMapper = webDavConfigMapper;
        this.appSecurityProperties = appSecurityProperties;
    }

    public WebDavTestResponse testConnection(WebDavTestRequest request) {
        String baseUrl = normalizeAndValidateBaseUrl(request.getBaseUrl());
        String username = requireNonBlank(
                request.getUsername(),
                "WEBDAV_INVALID_USERNAME",
                "用户名不能为空",
                "请检查用户名后重试");
        String password = requireNonBlank(
                request.getPassword(),
                "WEBDAV_INVALID_PASSWORD",
                "密码不能为空",
                "请检查密码后重试");
        String rootPath = normalizeRootPathForStorage(request.getRootPath());

        long start = System.currentTimeMillis();
        WebDavConnectResult result = webDavClient.testConnection(baseUrl, username, password, rootPath);
        return toTestResponse(result, System.currentTimeMillis() - start);
    }

    public WebDavTestResponse testSavedConnection(Long configId) {
        WebDavConfigEntity config = loadConfigForTest(configId);

        long start = System.currentTimeMillis();
        WebDavConnectResult result = webDavClient.testConnection(
                config.getBaseUrl(),
                config.getUsername(),
                decryptPassword(config.getPasswordEnc()),
                config.getRootPath());
        markRecoveryFromConnectResult(config.getId(), result);
        return toTestResponse(result, System.currentTimeMillis() - start);
    }

    public WebDavRecoveryStatusResponse getRecoveryStatus(Long configId) {
        WebDavConfigEntity config = loadConfigForTest(configId);
        RecoveryState state = recoveryStateByConfig.get(config.getId());
        if (state == null) {
            return new WebDavRecoveryStatusResponse(
                    RECOVERY_HEALTHY,
                    config.getId(),
                    "WEBDAV_RECOVERY_OK",
                    "连接状态正常",
                    null);
        }
        return new WebDavRecoveryStatusResponse(
                state.status,
                config.getId(),
                state.code,
                state.message,
                state.userAction);
    }

    public WebDavRecoveryStatusResponse adminRecover(AdminWebDavRecoveryRequest request, String actor) {
        WebDavConfigEntity config = loadConfigForTest(request == null ? null : request.getConfigId());
        updateRecoveryState(config.getId(), RECOVERY_RECOVERING, "WEBDAV_RECOVERY_IN_PROGRESS", "正在执行管理员恢复", null);

        boolean changed = applyRecoveryPatch(config, request);
        if (changed) {
            webDavConfigMapper.updateById(config);
        }

        WebDavConnectResult result = webDavClient.testConnection(
                config.getBaseUrl(),
                config.getUsername(),
                decryptPassword(config.getPasswordEnc()),
                config.getRootPath());

        String recoveryStatus = resolveRecoveryStatusFromCode(result.getCode(), result.isSuccess());
        updateRecoveryState(config.getId(), recoveryStatus, result.getCode(), result.getMessage(), result.getUserAction());

        String outcome = result.isSuccess() ? "success" : "failed";
        log.info("WEBDAV_RECOVERY_AUDIT actor={} action=admin-recover configId={} outcome={} code={}",
                actor == null ? "unknown" : actor, config.getId(), outcome, result.getCode());

        return new WebDavRecoveryStatusResponse(
                recoveryStatus,
                config.getId(),
                result.getCode(),
                result.getMessage(),
                result.getUserAction());
    }

    public WebDavConfigResponse createConfig(CreateWebDavConfigRequest request) {
        String name = requireNonBlank(
                request.getName(),
                "WEBDAV_INVALID_NAME",
                "配置名称不能为空",
                "请填写配置名称后重试");
        String baseUrl = normalizeAndValidateBaseUrl(request.getBaseUrl());
        String username = requireNonBlank(
                request.getUsername(),
                "WEBDAV_INVALID_USERNAME",
                "用户名不能为空",
                "请检查用户名后重试");
        String password = requireNonBlank(
                request.getPassword(),
                "WEBDAV_INVALID_PASSWORD",
                "密码不能为空",
                "请检查密码后重试");
        String rootPath = normalizeRootPathForStorage(request.getRootPath());

        WebDavConnectResult testResult = webDavClient.testConnection(baseUrl, username, password, rootPath);
        if (!testResult.isSuccess()) {
            throw new BusinessException(testResult.getCode(), testResult.getMessage(), testResult.getUserAction());
        }

        WebDavConfigEntity entity = new WebDavConfigEntity();
        entity.setName(name);
        entity.setBaseUrl(baseUrl);
        entity.setUsername(username);
        entity.setPasswordEnc(AesCryptoUtil.encrypt(password, appSecurityProperties.getEncryptKey()));
        entity.setRootPath(rootPath);
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);

        try {
            webDavConfigMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("WEBDAV_CONFIG_DUPLICATE", "配置名称已存在，请使用其他名称", "请修改配置名称后重试");
        }

        WebDavConfigEntity saved = webDavConfigMapper.selectById(entity.getId());
        updateRecoveryState(saved.getId(), RECOVERY_HEALTHY, "WEBDAV_RECOVERY_OK", "配置可用", null);
        return toResponse(saved);
    }

    public WebDavConfigResponse getConfig(Long id) {
        WebDavConfigEntity entity = webDavConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("WEBDAV_CONFIG_NOT_FOUND", "WebDAV配置不存在", "请联系管理员确认配置是否已创建");
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
            throw new BusinessException("WEBDAV_LIST_FAILED", e.getMessage(), "请稍后重试；若持续失败请联系管理员");
        } finally {
            webDavClient.closeSession(session);
        }
    }

    public void deleteDirectory(Long configId, String path) {
        String normalizedPath = normalizeRelativePath(path, true);
        if (normalizedPath.isEmpty()) {
            throw new BusinessException("400", "不允许删除根目录", "请选择子目录后重试");
        }

        WebDavConfigEntity config = loadConfig(configId);
        String password = decryptPassword(config.getPasswordEnc());
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());
        String targetUrl = buildDirectoryUrl(rootUrl, normalizedPath);
        try {
            webDavClient.delete(config.getUsername(), password, targetUrl);
        } catch (IllegalStateException e) {
            throw new BusinessException("WEBDAV_DELETE_FAILED", e.getMessage(), "请稍后重试；若持续失败请联系管理员");
        }
    }

    private WebDavConfigEntity loadConfigForTest(Long configId) {
        WebDavConfigEntity config;
        if (configId == null) {
            config = webDavConfigMapper.selectFirstEnabled();
        } else {
            config = webDavConfigMapper.selectById(configId);
        }

        if (config == null) {
            throw new BusinessException(
                    "WEBDAV_CONFIG_NOT_FOUND",
                    "未找到可用的 WebDAV 配置",
                    "请联系管理员先完成后端 WebDAV 配置");
        }
        return config;
    }

    private boolean applyRecoveryPatch(WebDavConfigEntity config, AdminWebDavRecoveryRequest request) {
        if (config == null || request == null) {
            return false;
        }

        boolean changed = false;

        String baseUrl = request.getBaseUrl() == null || request.getBaseUrl().trim().isEmpty()
                ? config.getBaseUrl()
                : normalizeAndValidateBaseUrl(request.getBaseUrl());
        if (!baseUrl.equals(config.getBaseUrl())) {
            config.setBaseUrl(baseUrl);
            changed = true;
        }

        String username = request.getUsername() == null || request.getUsername().trim().isEmpty()
                ? config.getUsername()
                : request.getUsername().trim();
        if (!username.equals(config.getUsername())) {
            config.setUsername(username);
            changed = true;
        }

        if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
            String passwordEnc = AesCryptoUtil.encrypt(request.getPassword().trim(), appSecurityProperties.getEncryptKey());
            if (!passwordEnc.equals(config.getPasswordEnc())) {
                config.setPasswordEnc(passwordEnc);
                changed = true;
            }
        }

        if (request.getRootPath() != null) {
            String normalizedRootPath = normalizeRootPathForStorage(request.getRootPath());
            if (!normalizedRootPath.equals(config.getRootPath())) {
                config.setRootPath(normalizedRootPath);
                changed = true;
            }
        }

        if (request.getEnabled() != null) {
            int enabled = Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0;
            if (config.getEnabled() == null || config.getEnabled() != enabled) {
                config.setEnabled(enabled);
                changed = true;
            }
        }

        return changed;
    }

    private void markRecoveryFromConnectResult(Long configId, WebDavConnectResult result) {
        if (configId == null) {
            return;
        }
        if (result == null) {
            updateRecoveryState(configId, RECOVERY_FAILED, "WEBDAV_TEST_UNKNOWN", "WebDAV连接测试失败", "请稍后重试");
            return;
        }
        String status = resolveRecoveryStatusFromCode(result.getCode(), result.isSuccess());
        updateRecoveryState(configId, status, result.getCode(), result.getMessage(), result.getUserAction());
    }

    private String resolveRecoveryStatusFromCode(String code, boolean success) {
        if (success) {
            return RECOVERY_HEALTHY;
        }
        if (code != null && AUTH_RECOVERY_CODES.contains(code)) {
            return RECOVERY_NEEDS_REAUTH;
        }
        return RECOVERY_FAILED;
    }

    private void updateRecoveryState(Long configId,
                                     String status,
                                     String code,
                                     String message,
                                     String userAction) {
        if (configId == null) {
            return;
        }
        RecoveryState state = new RecoveryState();
        state.status = status;
        state.code = code == null ? "WEBDAV_TEST_UNKNOWN" : code;
        state.message = message == null ? "WebDAV连接测试失败" : message;
        state.userAction = userAction;
        recoveryStateByConfig.put(configId, state);
    }

    private WebDavConfigEntity loadConfig(Long configId) {
        WebDavConfigEntity config = webDavConfigMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException("WEBDAV_CONFIG_NOT_FOUND", "WebDAV配置不存在", "请联系管理员确认配置是否已创建");
        }
        return config;
    }

    private String decryptPassword(String passwordEnc) {
        return AesCryptoUtil.decrypt(passwordEnc, appSecurityProperties.getEncryptKey());
    }

    private String normalizeAndValidateBaseUrl(String value) {
        String baseUrl = requireNonBlank(
                value,
                "WEBDAV_INVALID_BASE_URL",
                "WebDAV地址不能为空",
                "请检查 WebDAV 服务器地址后重试");

        try {
            URI uri = new URI(baseUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new BusinessException(
                        "WEBDAV_INVALID_BASE_URL",
                        "WebDAV地址仅支持 http/https 协议",
                        "请检查地址协议后重试");
            }
            if (uri.getHost() == null && uri.getRawAuthority() == null) {
                throw new BusinessException(
                        "WEBDAV_INVALID_BASE_URL",
                        "WebDAV地址缺少主机名",
                        "请检查地址后重试");
            }
            if (uri.getRawUserInfo() != null) {
                throw new BusinessException(
                        "WEBDAV_INVALID_BASE_URL",
                        "WebDAV地址中禁止内嵌账号信息",
                        "请移除地址中的账号密码后重试");
            }

            URI normalized = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    uri.getRawAuthority(),
                    uri.getPath(),
                    null,
                    null);
            return normalized.toASCIIString();
        } catch (URISyntaxException e) {
            throw new BusinessException(
                    "WEBDAV_INVALID_BASE_URL",
                    "WebDAV地址格式不合法",
                    "请检查地址后重试");
        }
    }

    private String normalizeRootPathForStorage(String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            return "/";
        }

        String normalized = rootPath.trim().replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        for (String segment : normalized.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(
                        "WEBDAV_INVALID_ROOT_PATH",
                        "根目录路径不合法",
                        "请检查目录路径后重试");
            }
        }

        return normalized;
    }

    private String requireNonBlank(String value, String code, String message, String userAction) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(code, message, userAction);
        }
        return value.trim();
    }

    private String normalizeRelativePath(String path, boolean required) {
        if (path == null || path.trim().isEmpty()) {
            if (required) {
                throw new BusinessException("400", "目录路径不能为空", "请输入目录路径后重试");
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
                throw new BusinessException("400", "目录路径不合法", "请检查目录路径后重试");
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

    private WebDavTestResponse toTestResponse(WebDavConnectResult result, long latencyMs) {
        String safeStatus = result == null || result.getStatus() == null ? "FAILED" : result.getStatus();
        String safeDirectoryAccess = result == null || result.getDirectoryAccess() == null
                ? "UNREACHABLE"
                : result.getDirectoryAccess();
        String safeCode = result == null || result.getCode() == null
                ? "WEBDAV_TEST_UNKNOWN"
                : result.getCode();
        String safeMessage = result == null || result.getMessage() == null
                ? "WebDAV连接测试失败"
                : result.getMessage();

        return new WebDavTestResponse(
                safeStatus,
                safeDirectoryAccess,
                latencyMs,
                safeCode,
                safeMessage,
                result == null ? null : result.getUserAction());
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

    private static class RecoveryState {

        private String status;
        private String code;
        private String message;
        private String userAction;
    }
}
