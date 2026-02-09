package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.webdav")
public class AppWebDavProperties {

    private int connectTimeoutMs = 5000;

    private int socketTimeoutMs = 15000;
}
