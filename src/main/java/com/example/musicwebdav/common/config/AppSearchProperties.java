package com.example.musicwebdav.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.search")
public class AppSearchProperties {

    /**
     * Slow query threshold used for structured outlier logging.
     */
    private long slowQueryThresholdMs = 800L;

    /**
     * In-memory query cache TTL to absorb repeated keyword bursts.
     */
    private long cacheTtlMs = 5000L;

    /**
     * Max cache entries for search query snapshots.
     */
    private int cacheMaxEntries = 256;

    /**
     * P95 target used for baseline governance and warning logs.
     */
    private long p95TargetMs = 800L;
}
