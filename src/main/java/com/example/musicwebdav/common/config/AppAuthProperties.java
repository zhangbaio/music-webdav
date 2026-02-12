package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    /**
     * Demo account username for MVP authentication bootstrap.
     */
    private String username = "demo";

    /**
     * Demo account password for MVP authentication bootstrap.
     */
    private String password = "demo123";

    /**
     * JWT issuer claim.
     */
    private String issuer = "music-webdav";

    /**
     * JWT audience claim.
     */
    private String audience = "applemusic-mobile";

    /**
     * HMAC secret used for JWT signature.
     */
    private String jwtSecret = "changeit-music-webdav-jwt-secret";

    /**
     * Access token TTL in seconds.
     */
    private long accessTokenTtlSeconds = 900;

    /**
     * Refresh token TTL in seconds.
     */
    private long refreshTokenTtlSeconds = 2592000;

    /**
     * Whether refresh endpoint rotates refresh token.
     */
    private boolean refreshTokenRotate = true;
}
