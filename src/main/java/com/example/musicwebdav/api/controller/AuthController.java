package com.example.musicwebdav.api.controller;

import com.example.musicwebdav.api.request.AuthLoginRequest;
import com.example.musicwebdav.api.request.AuthLogoutRequest;
import com.example.musicwebdav.api.request.AuthRefreshRequest;
import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.api.response.AuthTokenResponse;
import com.example.musicwebdav.application.service.AuthTokenService;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthTokenService authTokenService;

    public AuthController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return ApiResponse.success(authTokenService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody AuthRefreshRequest request) {
        return ApiResponse.success(authTokenService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(@RequestBody(required = false) AuthLogoutRequest request) {
        authTokenService.logout(request == null ? null : request.getRefreshToken());
        return ApiResponse.success("OK");
    }
}
