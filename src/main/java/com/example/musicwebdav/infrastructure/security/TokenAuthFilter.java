package com.example.musicwebdav.infrastructure.security;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.application.service.AuthTokenService;
import com.example.musicwebdav.application.service.PlaybackTokenService;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
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

    public TokenAuthFilter(AppSecurityProperties properties,
                           AuthTokenService authTokenService,
                           PlaybackTokenService playbackTokenService) {
        this.properties = properties;
        this.authTokenService = authTokenService;
        this.playbackTokenService = playbackTokenService;
        this.objectMapper = new ObjectMapper();
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

        String configuredApiToken = properties.getApiToken();
        if (configuredApiToken != null
                && !configuredApiToken.trim().isEmpty()
                && configuredApiToken.equals(bearerToken)) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            "api-client",
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_API")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String subject = authTokenService.verifyAccessTokenAndGetSubject(bearerToken);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            subject,
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BusinessException e) {
            unauthorized(response, e.getCode(), e.getMessage(), e.getUserAction());
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

        String description = message == null || message.trim().isEmpty()
                ? "authentication failed"
                : sanitizeAuthHeaderValue(message);
        return "Bearer error=\"" + error + "\", error_description=\"" + description + "\"";
    }

    private String sanitizeAuthHeaderValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') {
                continue;
            }
            if (c > 127) {
                continue;
            }
            sb.append(c);
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "authentication failed" : result;
    }
}
