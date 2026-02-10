package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.playlist")
public class AppPlaylistProperties {

    private boolean cleanupEnabled = false;

    private String cleanupCron = "0 30 4 * * ?";

    private boolean cleanupNormalizeOrderNo = true;
}
