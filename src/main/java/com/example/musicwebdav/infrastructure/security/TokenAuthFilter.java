package com.example.musicwebdav.infrastructure.security;

import com.example.musicwebdav.api.response.ApiResponse;
import com.example.musicwebdav.application.service.AuthTokenService;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class TokenAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AppSecurityProperties properties;
    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;

    public TokenAuthFilter(AppSecurityProperties properties,
                           AuthTokenService authTokenService) {
        this.properties = properties;
        this.authTokenService = authTokenService;
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
                || uri.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestToken = extractRequestToken(request);
        if (requestToken == null || requestToken.isEmpty()) {
            unauthorized(response, "AUTH_MISSING_TOKEN", "缺少 Bearer token", "请先登录");
            return;
        }

        String configuredApiToken = properties.getApiToken();
        if (configuredApiToken != null
                && !configuredApiToken.trim().isEmpty()
                && configuredApiToken.equals(requestToken)) {
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
            String subject = authTokenService.verifyAccessTokenAndGetSubject(requestToken);
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

    private String extractRequestToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        String uri = request.getRequestURI();
        if (uri != null
                && uri.startsWith("/api/v1/tracks/")
                && (uri.endsWith("/stream") || uri.endsWith("/stream-proxy"))) {
            String tokenInQuery = request.getParameter("token");
            return tokenInQuery == null ? null : tokenInQuery.trim();
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response,
                              String code,
                              String message,
                              String userAction) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(code, message, userAction);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
