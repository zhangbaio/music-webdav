package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.AdminWebDavRecoveryRequest;
import com.example.musicwebdav.api.request.CreateWebDavConfigRequest;
import com.example.musicwebdav.api.request.WebDavTestRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.WebDavConfigResponse;
import com.example.musicwebdav.api.response.WebDavDirectoryItemResponse;
import com.example.musicwebdav.api.response.WebDavRecoveryStatusResponse;
import com.example.musicwebdav.api.response.WebDavTestResponse;
import com.example.musicwebdav.application.service.WebDavConnectionService;
import java.util.Collection;
import java.util.List;
import javax.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webdav")
public class WebDavController {

    private static final String ROLE_API = "ROLE_API";

    private final WebDavConnectionService webDavConnectionService;

    public WebDavController(WebDavConnectionService webDavConnectionService) {
        this.webDavConnectionService = webDavConnectionService;
    }

    @PostMapping("/test")
    public ApiResponse<WebDavTestResponse> testConnection(@Valid @RequestBody WebDavTestRequest request) {
        return ApiResponse.success(webDavConnectionService.testConnection(request));
    }

    @PostMapping("/configs/test")
    public ApiResponse<WebDavTestResponse> testSavedConnection(
            @RequestParam(value = "id", required = false) Long configId) {
        return ApiResponse.success(webDavConnectionService.testSavedConnection(configId));
    }

    @GetMapping("/recovery/status")
    public ApiResponse<WebDavRecoveryStatusResponse> recoveryStatus(
            @RequestParam(value = "id", required = false) Long configId) {
        return ApiResponse.success(webDavConnectionService.getRecoveryStatus(configId));
    }

    @PostMapping("/recovery/admin")
    public ResponseEntity<ApiResponse<WebDavRecoveryStatusResponse>> adminRecover(
            @RequestBody(required = false) AdminWebDavRecoveryRequest request) {
        if (!hasApiRole()) {
            ApiResponse<WebDavRecoveryStatusResponse> body = ApiResponse.fail(
                    "AUTH_INSUFFICIENT_SCOPE",
                    "当前凭据无管理员恢复权限",
                    "请使用具备 api-token 权限的凭据重试");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer error=\"insufficient_scope\", error_description=\"api-token required\"")
                    .body(body);
        }

        AdminWebDavRecoveryRequest safeRequest = request == null ? new AdminWebDavRecoveryRequest() : request;
        String actor = currentActor();
        return ResponseEntity.ok(ApiResponse.success(webDavConnectionService.adminRecover(safeRequest, actor)));
    }

    @PostMapping("/configs")
    public ApiResponse<WebDavConfigResponse> createConfig(@Valid @RequestBody CreateWebDavConfigRequest request) {
        return ApiResponse.success(webDavConnectionService.createConfig(request));
    }

    @GetMapping("/configs/{id}")
    public ApiResponse<WebDavConfigResponse> getConfig(@PathVariable("id") Long id) {
        return ApiResponse.success(webDavConnectionService.getConfig(id));
    }

    @GetMapping("/configs")
    public ApiResponse<List<WebDavConfigResponse>> listConfigs() {
        return ApiResponse.success(webDavConnectionService.listConfigs());
    }

    @GetMapping("/configs/{id}/directories")
    public ApiResponse<List<WebDavDirectoryItemResponse>> listDirectories(@PathVariable("id") Long id,
                                                                          @RequestParam(value = "path", required = false) String path) {
        return ApiResponse.success(webDavConnectionService.listDirectories(id, path));
    }

    @DeleteMapping("/configs/{id}/directories")
    public ApiResponse<String> deleteDirectory(@PathVariable("id") Long id,
                                               @RequestParam("path") String path) {
        webDavConnectionService.deleteDirectory(id, path);
        return ApiResponse.success("OK");
    }

    private boolean hasApiRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities == null) {
            return false;
        }

        for (GrantedAuthority authority : authorities) {
            if (ROLE_API.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().trim().isEmpty()) {
            return "unknown";
        }
        return authentication.getName();
    }
}
