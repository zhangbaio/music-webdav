package com.example.musicwebdav.common.config;

import com.example.musicwebdav.common.logging.AccessLogFilter;
import com.example.musicwebdav.infrastructure.security.TokenAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public AccessLogFilter accessLogFilter() {
        return new AccessLogFilter();
    }

    @Bean
    public TokenAuthFilter tokenAuthFilter(AppSecurityProperties properties) {
        return new TokenAuthFilter(properties);
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
                        "/swagger-ui.html")
                .permitAll()
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(accessLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tokenAuthFilter, AccessLogFilter.class);

        return http.build();
    }
}
