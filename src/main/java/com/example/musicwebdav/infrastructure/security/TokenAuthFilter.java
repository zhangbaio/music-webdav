package com.example.musicwebdav.infrastructure.security;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.application.service.AuthTokenService;
import com.example.musicwebdav.application.service.PlaybackTokenService;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.common.security.UserPrincipal;
import com.example.musicwebdav.common.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern PLAYBACK_STREAM_URI_PATTERN =
            Pattern.compile("^/api/v1/tracks/(\\d+)/(stream|stream-proxy)$");

    private final AppSecurityProperties properties;
    private final AuthTokenService authTokenService;
    private final PlaybackTokenService playbackTokenService;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;

    public TokenAuthFilter(AppSecurityProperties properties,
                           AuthTokenService authTokenService,
                           PlaybackTokenService playbackTokenService,
                           JwtUtil jwtUtil) {
        this.properties = properties;
        this.authTokenService = authTokenService;
        this.playbackTokenService = playbackTokenService;
        this.objectMapper = new ObjectMapper();
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health")
                || uri.startsWith("/actuator/info")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/swagger-ui.html")
                || uri.startsWith("/music-player.html")
                || uri.startsWith("/favicon.ico")
                || uri.startsWith("/error")
                || uri.startsWith("/api/v1/auth/")
                || isSignedStreamRequest(uri);
    }

    /**
     * Signed stream endpoints use HMAC signature for auth, not Bearer token.
     * Pattern: /api/v1/tracks/{id}/stream-signed
     */
    private boolean isSignedStreamRequest(String uri) {
        return uri != null && uri.contains("/stream-signed");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String bearerToken = extractBearerToken(request);
        String uri = request.getRequestURI();
        if (bearerToken == null || bearerToken.isEmpty()) {
            if (!isPlaybackStreamRequest(uri)) {
                unauthorized(response, "AUTH_MISSING_TOKEN", "缺少 Bearer token", "请先登录");
                return;
            }
            authenticatePlaybackToken(request, response, filterChain);
            return;
        }

        // Support Legacy static token for API clients if configured
        String configuredApiToken = properties.getApiToken();
        if (configuredApiToken != null
                && !configuredApiToken.trim().isEmpty()
                && configuredApiToken.equals(bearerToken)) {
            UserPrincipal principal = new UserPrincipal(1L, "api-client", "", "API");
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        // Verify JWT Token
        try {
            if (!jwtUtil.validateToken(bearerToken)) {
                unauthorized(response, "AUTH_ACCESS_TOKEN_INVALID", "Token 无效或已过期", "请重新登录");
                return;
            }
            Long userId = jwtUtil.getUserId(bearerToken);
            String username = jwtUtil.getUsername(bearerToken);
            
            // For now, we assume role USER. In real app, load from DB or JWT claims.
            UserPrincipal principal = new UserPrincipal(userId, username, "", "USER");
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            unauthorized(response, "AUTH_ACCESS_TOKEN_INVALID", "Token 校验失败: " + e.getMessage(), "请重新登录");
        }
    }

    private void authenticatePlaybackToken(HttpServletRequest request,
                                           HttpServletResponse response,
                                           FilterChain filterChain) throws IOException, ServletException {
        String playbackToken = request.getParameter("playbackToken");
        if (playbackToken == null || playbackToken.trim().isEmpty()) {
            unauthorized(response, "PLAYBACK_TOKEN_INVALID", "缺少 playbackToken", "请重试播放");
            return;
        }

        Long trackId = resolveTrackId(request.getRequestURI());
        if (trackId == null || trackId <= 0) {
            unauthorized(response, "PLAYBACK_TOKEN_INVALID", "播放路径无效", "请重试播放");
            return;
        }

        try {
            String subject = playbackTokenService.verifyTrackStreamTokenAndGetSubject(playbackToken.trim(), trackId);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            subject,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_PLAYBACK")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BusinessException e) {
            unauthorized(response, e.getCode(), e.getMessage(), e.getUserAction());
        }
    }

    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private boolean isPlaybackStreamRequest(String uri) {
        if (uri == null) {
            return false;
        }
        return PLAYBACK_STREAM_URI_PATTERN.matcher(uri).matches();
    }

    private Long resolveTrackId(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher matcher = PLAYBACK_STREAM_URI_PATTERN.matcher(uri);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void unauthorized(HttpServletResponse response,
                              String code,
                              String message,
                              String userAction) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, buildWwwAuthenticateValue(code, message));
        ApiResponse<Void> body = ApiResponse.fail(code, message, userAction);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String buildWwwAuthenticateValue(String code, String message) {
        String error = "invalid_token";
        if (code == null || code.trim().isEmpty()) {
            error = "invalid_request";
        } else if (!(code.startsWith("AUTH_") || "AUTH_MISSING_TOKEN".equals(code))) {
            error = "invalid_request";
        }

        String description = resolveAuthHeaderDescription(code, message);
        return "Bearer error=\"" + error + "\", error_description=\"" + description + "\"";
    }

    private String resolveAuthHeaderDescription(String code, String message) {
        if ("AUTH_MISSING_TOKEN".equals(code)) {
            return "missing bearer token";
        }
        if ("PLAYBACK_TOKEN_INVALID".equals(code)) {
            return "invalid playback token";
        }
        if ("AUTH_ACCESS_TOKEN_EXPIRED".equals(code)) {
            return "access token expired";
        }
        if ("AUTH_ACCESS_TOKEN_INVALID".equals(code)) {
            return "access token invalid";
        }

        String sanitized = sanitizeAuthHeaderValue(message);
        if (sanitized.isEmpty()) {
            return "authentication failed";
        }
        return sanitized;
    }

    private String sanitizeAuthHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\\", "").replace("\"", "").trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                builder.append(c);
            } else {
                builder.append(' ');
            }
        }
        return builder.toString().replaceAll("\\s+", " ").trim();
    }
}
