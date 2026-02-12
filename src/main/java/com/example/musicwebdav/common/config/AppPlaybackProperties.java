package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.playback")
public class AppPlaybackProperties {

    /**
     * Short-lived playback token TTL in seconds.
     */
    private long tokenTtlSeconds = 60;

    /**
     * Frontend should refresh token when remaining seconds are below this threshold.
     */
    private long refreshBeforeExpirySeconds = 8;
}
