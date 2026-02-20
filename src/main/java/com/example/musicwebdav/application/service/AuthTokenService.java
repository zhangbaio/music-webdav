package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.AuthTokenResponse;
import com.example.musicwebdav.common.config.AppAuthProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.util.JwtUtil;
import com.example.musicwebdav.infrastructure.persistence.entity.UserEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.UserMapper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);
    private static final String CODE_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    private static final String CODE_REFRESH_INVALID = "AUTH_REFRESH_TOKEN_INVALID";

    private final AppAuthProperties properties;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthTokenService(AppAuthProperties properties, 
                           UserMapper userMapper, 
                           JwtUtil jwtUtil,
                           PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthTokenResponse login(String username, String password) {
        UserEntity user = userMapper.selectByUsername(username);
        if (user == null) {
            log.warn("LOGIN_FAILED reason=USER_NOT_FOUND username={}", username);
            throw new BusinessException(CODE_INVALID_CREDENTIALS, "用户名或密码错误", "请检查账号密码后重试");
        }
        
        boolean matches = passwordEncoder.matches(password, user.getPasswordHash());
        if (!matches) {
            log.warn("LOGIN_FAILED reason=PASSWORD_MISMATCH username={}", username);
            throw new BusinessException(CODE_INVALID_CREDENTIALS, "用户名或密码错误", "请检查账号密码后重试");
        }

        log.info("LOGIN_SUCCESS userId={} username={}", user.getId(), username);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        // For simplicity in this demo migration, we use the same token for access and refresh
        // In production, separate them with different TTLs
        return new AuthTokenResponse(token, token, "Bearer", 86400L, 86400L);
    }

    public AuthTokenResponse refresh(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(CODE_REFRESH_INVALID, "Refresh Token 无效或已过期", "请重新登录");
        }
        Long userId = jwtUtil.getUserId(refreshToken);
        String username = jwtUtil.getUsername(refreshToken);
        
        String newToken = jwtUtil.generateToken(userId, username);
        return new AuthTokenResponse(newToken, newToken, "Bearer", 86400L, 86400L);
    }

    public void logout(String refreshToken) {
        // Stateless logout (client side should discard token)
    }

    public String verifyAccessTokenAndGetSubject(String accessToken) {
        if (!jwtUtil.validateToken(accessToken)) {
            throw new BusinessException("AUTH_ACCESS_TOKEN_INVALID", "Access Token 无效", "请重新登录");
        }
        return jwtUtil.getUsername(accessToken);
    }
}
