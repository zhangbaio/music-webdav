package com.example.musicwebdav.common.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.scan")
public class AppScanProperties {

    private int batchSize = 300;

    private int parserThreadCount = 6;

    private int maxRetry = 3;

    private int retryBackoffMs = 500;

    private List<String> audioExtensions = new ArrayList<>(Arrays.asList("mp3", "flac", "m4a", "aac", "ogg", "wav"));

    private String incrementalCron = "0 0 3 * * ?";

    private int dbBatchSize = 50;

    private int progressPersistIntervalSec = 30;

    private int metadataHeadBytes = 131072;

    private int metadataTailBytes = 128;

    public Set<String> normalizedAudioExtensions() {
        return audioExtensions.stream()
                .filter(item -> item != null && !item.trim().isEmpty())
                .map(item -> item.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
