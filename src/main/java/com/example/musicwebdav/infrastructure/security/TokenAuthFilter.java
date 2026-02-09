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
                || uri.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String configuredToken = properties.getApiToken();
        String authHeader = request.getHeader("Authorization");

        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            unauthorized(response, "API token is not configured");
            return;
        }

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "Missing Bearer token");
            return;
        }

        String requestToken = authHeader.substring(BEARER_PREFIX.length()).trim();
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

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"401\",\"message\":\"" + message + "\",\"data\":null}");
    }
}
