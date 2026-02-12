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

    private List<String> lyricExtensions = new ArrayList<>(Arrays.asList("lrc", "txt"));

    private String incrementalCron = "0 0 3 * * ?";

    private int dbBatchSize = 50;

    private int progressPersistIntervalSec = 30;

    private int metadataHeadBytes = 131072;

    private int metadataTailBytes = 128;

    /**
     * Parallel worker count for directory file-processing stage.
     */
    private int directoryProcessThreadCount = 4;

    /**
     * Max in-flight directory processing jobs.
     */
    private int directoryProcessMaxInFlight = 12;

    /**
     * Parallel worker count for directory listing stage.
     */
    private int directoryListThreadCount = 4;

    /**
     * Max in-flight directory listing jobs.
     */
    private int directoryListMaxInFlight = 24;

    /**
     * Whether incremental scan can skip unchanged directories by signature (etag/mtime/child count).
     */
    private boolean incrementalDirectorySkipEnabled = true;

    /**
     * Whether full scan can skip unchanged directories by signature.
     */
    private boolean fullDirectorySkipEnabled = true;

    /**
     * Whether full scan performs soft-delete detection.
     */
    private boolean fullEnableDeleteDetection = true;

    /**
     * Whether full scan runs deduplication.
     */
    private boolean fullEnableDedup = true;

    /**
     * Minimum changed rows (added + updated + deleted) to trigger dedup in FULL scan.
     */
    private int dedupMinChangedCount = 200;

    /**
     * Minimum changed ratio to trigger dedup in FULL scan.
     */
    private double dedupMinChangedRatio = 0.01D;

    /**
     * Always run dedup for small libraries when there is any change.
     */
    private int dedupAlwaysForSmallLibraryMaxFiles = 5000;

    /**
     * Whether full scan uses seen-file fallback for delete detection when directory skip is enabled.
     * Disable this to reduce DB write amplification, then rely on last_scan_task_id + directory prefix touch.
     */
    private boolean fullSeenDeleteFallbackEnabled = false;

    /**
     * Batch size for bulk write operations (seen-file inserts, touch updates).
     * These are simple operations so can use larger batches than track upserts.
     * Defaults to dbBatchSize * 2 if not set (0).
     */
    private int bulkWriteBatchSize = 0;

    /**
     * Max audio file count for a directory to be considered "small" and merged with other small dirs
     * into a single process task. Reduces thread scheduling overhead for many tiny directories.
     * Set to 0 to disable merging.
     */
    private int smallDirMergeThreshold = 5;

    /**
     * Warn in logs when a directory contains more audio files than this threshold.
     * Helps identify bottleneck directories during large scans. Set to 0 to disable.
     */
    private int largeDirWarnThreshold = 500;

    /**
     * Whether incremental scan performs soft-delete detection.
     * Keep false for performance; use periodic FULL scan to reconcile deletions.
     */
    private boolean incrementalEnableDeleteDetection = false;

    /**
     * Whether incremental scan runs deduplication.
     * Keep false for performance; run dedup on FULL scan or scheduled maintenance.
     */
    private boolean incrementalEnableDedup = false;

    public Set<String> normalizedAudioExtensions() {
        return audioExtensions.stream()
                .filter(item -> item != null && !item.trim().isEmpty())
                .map(item -> item.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> normalizedLyricExtensions() {
        return lyricExtensions.stream()
                .filter(item -> item != null && !item.trim().isEmpty())
                .map(item -> item.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
