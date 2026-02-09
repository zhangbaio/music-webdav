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
}
