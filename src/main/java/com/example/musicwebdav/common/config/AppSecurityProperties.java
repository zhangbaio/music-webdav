package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    /**
     * Simple static token for v1 skeleton.
     */
    private String apiToken = "dev-token";

    /**
     * AES key for WebDAV credential encryption (16/24/32 chars).
     */
    private String encryptKey = "changeit12345678";

    /**
     * HMAC-SHA256 key for signing playback stream URLs.
     * Must be at least 16 characters. Used by PlaybackSignUtil.
     */
    private String playbackSignKey = "change-me-playback-sign-key-32b";

    /**
     * Time-to-live in seconds for signed playback URLs.
     * Default: 3600 (1 hour).
     */
    private int playbackSignTtlSec = 3600;
}
