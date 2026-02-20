package com.example.musicwebdav.common.config;

import com.example.musicwebdav.application.service.AuthTokenService;
import com.example.musicwebdav.application.service.PlaybackTokenService;
import com.example.musicwebdav.common.logging.AccessLogFilter;
import com.example.musicwebdav.common.util.JwtUtil;
import com.example.musicwebdav.infrastructure.security.TokenAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AccessLogFilter accessLogFilter() {
        return new AccessLogFilter();
    }

    @Bean
    public TokenAuthFilter tokenAuthFilter(AppSecurityProperties properties,
                                           AuthTokenService authTokenService,
                                           PlaybackTokenService playbackTokenService,
                                           JwtUtil jwtUtil) {
        return new TokenAuthFilter(properties, authTokenService, playbackTokenService, jwtUtil);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AccessLogFilter accessLogFilter,
                                                   TokenAuthFilter tokenAuthFilter) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers(
                        "/actuator/health",
                        "/actuator/info",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/music-player.html",
                        "/webdav-manager.html",
                        "/favicon.ico",
                        "/api/v1/auth/**",
                        "/api/v1/tracks/*/stream-signed")
                .permitAll()
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(accessLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tokenAuthFilter, AccessLogFilter.class);

        return http.build();
    }
}
