package com.example.musicwebdav.infrastructure.security;

import com.example.musicwebdav.common.config.AppSecurityProperties;
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

    public TokenAuthFilter(AppSecurityProperties properties) {
        this.properties = properties;
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
        String configuredToken = properties.getApiToken();
        String requestToken = extractRequestToken(request);

        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            unauthorized(response, "API token is not configured");
            return;
        }

        if (requestToken == null || requestToken.isEmpty()) {
            unauthorized(response, "Missing Bearer token");
            return;
        }

        if (!configuredToken.equals(requestToken)) {
            unauthorized(response, "Invalid token");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "api-client",
                        null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
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

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"401\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
