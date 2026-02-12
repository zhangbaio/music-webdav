package com.example.musicwebdav.application.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks and reports scan progress with two-phase reporting, smoothed ETA,
 * milestone logging, and large-directory warnings.
 *
 * <p>Phase 1 (DISCOVERY): Reports directory discovery speed and tree depth.
 * <p>Phase 2 (PROCESS): Reports file processing progress with stable ETA.
 */
public class ScanProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(ScanProgressTracker.class);

    // ── Phase enum ──────────────────────────────────────────
    public enum Phase {
        DISCOVERY, PROCESS
    }

    // ── Core counters ───────────────────────────────────────
    private final long startTimeMs;
    private int totalDirectoriesDiscovered;
    private int completedDirectories;
    private int skippedDirectories;
    private int totalFilesDiscovered;
    private int filesProcessed;
    private int filesAdded;
    private int filesUpdated;
    private int filesFailed;
    private int filesSkipped;
    private String lastSyncedDir;
    private Phase currentPhase = Phase.DISCOVERY;

    // ── Log / persist triggers ──────────────────────────────
    private long lastLogTimeMs;
    private int lastLogDirCount;
    private long lastPersistTimeMs;
    private int lastPersistDirCount;

    private final int logIntervalSec;
    private final int logIntervalDirs;
    private final int persistIntervalSec;
    private final int persistIntervalDirs;

    // ── Milestone tracking ──────────────────────────────────
    private int lastMilestonePct = -1;

    // ── Large directory warning threshold ───────────────────
    private final int largeDirWarnThreshold;

    // ── Smoothed ETA (sliding window) ───────────────────────
    private static final int ETA_WINDOW_SIZE = 10;
    private final Deque<EtaSample> etaWindow = new ArrayDeque<>();
    private int lastEtaSampleDirs = 0;
    private long lastEtaSampleTimeMs;

    public ScanProgressTracker(int logIntervalSec, int persistIntervalSec) {
        this(logIntervalSec, persistIntervalSec, 0);
    }

    public ScanProgressTracker(int logIntervalSec, int persistIntervalSec, int largeDirWarnThreshold) {
        this.startTimeMs = System.currentTimeMillis();
        this.lastLogTimeMs = this.startTimeMs;
        this.lastPersistTimeMs = this.startTimeMs;
        this.lastEtaSampleTimeMs = this.startTimeMs;
        this.logIntervalSec = logIntervalSec > 0 ? logIntervalSec : 30;
        this.logIntervalDirs = 10;
        this.persistIntervalSec = persistIntervalSec > 0 ? persistIntervalSec : 30;
        this.persistIntervalDirs = 5;
        this.largeDirWarnThreshold = largeDirWarnThreshold > 0 ? largeDirWarnThreshold : 0;
    }

    // ════════════════════════════════════════════════════════
    // Event callbacks
    // ════════════════════════════════════════════════════════

    public void onDirectoryDiscovered(String dirPath, int fileCount) {
        totalFilesDiscovered += fileCount;
        // Warn on large directories
        if (largeDirWarnThreshold > 0 && fileCount > largeDirWarnThreshold) {
            log.warn("LARGE_DIR_DETECTED dir={} fileCount={} threshold={} - may be slow to process",
                    dirPath, fileCount, largeDirWarnThreshold);
        }
    }

    public void addDiscoveredDirectories(int count) {
        totalDirectoriesDiscovered += count;
    }

    public void onDirectoryCompleted(String dirPath, int processed, int added, int updated, int skipped, int failed) {
        completedDirectories++;
        filesProcessed += processed;
        filesAdded += added;
        filesUpdated += updated;
        filesSkipped += skipped;
        filesFailed += failed;
        lastSyncedDir = dirPath;
        recordEtaSample();
        checkMilestone();
    }

    public void onDirectorySkipped(String dirPath) {
        completedDirectories++;
        skippedDirectories++;
        lastSyncedDir = dirPath;
        recordEtaSample();
        checkMilestone();
    }

    /**
     * Transition from DISCOVERY to PROCESS phase. Call this when the initial
     * directory tree enumeration is substantially complete and file processing
     * is the dominant activity.
     */
    public void enterProcessPhase() {
        if (currentPhase != Phase.PROCESS) {
            currentPhase = Phase.PROCESS;
            long elapsed = System.currentTimeMillis() - startTimeMs;
            log.info("SCAN_PHASE_TRANSITION phase=PROCESS totalDirs={} elapsed={}",
                    totalDirectoriesDiscovered, formatElapsed(elapsed));
        }
    }

    // ════════════════════════════════════════════════════════
    // Milestone detection
    // ════════════════════════════════════════════════════════

    private void checkMilestone() {
        if (totalDirectoriesDiscovered <= 0) {
            return;
        }
        int pct = (int) (completedDirectories * 100L / totalDirectoriesDiscovered);
        int currentBucket = pct / 10;
        int lastBucket = lastMilestonePct / 10;
        if (currentBucket > lastBucket && pct <= 100) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            log.info("SCAN_MILESTONE {}% | dirs={}/{} processed={} added={} updated={} "
                            + "skipped={} failed={} dirSkipped={} elapsed={}",
                    currentBucket * 10,
                    completedDirectories, totalDirectoriesDiscovered,
                    filesProcessed, filesAdded, filesUpdated,
                    filesSkipped, filesFailed, skippedDirectories,
                    formatElapsed(elapsed));
            lastMilestonePct = pct;
        }
    }

    // ════════════════════════════════════════════════════════
    // Smoothed ETA with sliding window
    // ════════════════════════════════════════════════════════

    private void recordEtaSample() {
        long now = System.currentTimeMillis();
        long deltaMs = now - lastEtaSampleTimeMs;
        int deltaDirs = completedDirectories - lastEtaSampleDirs;
        // Record a sample every 5 dirs or 10 seconds, whichever comes first
        if (deltaDirs >= 5 || deltaMs >= 10_000L) {
            if (deltaDirs > 0 && deltaMs > 0) {
                double dirsPerSec = deltaDirs * 1000.0 / deltaMs;
                etaWindow.addLast(new EtaSample(dirsPerSec));
                while (etaWindow.size() > ETA_WINDOW_SIZE) {
                    etaWindow.removeFirst();
                }
            }
            lastEtaSampleDirs = completedDirectories;
            lastEtaSampleTimeMs = now;
        }
    }

    private String formatSmoothedEta() {
        if (etaWindow.isEmpty() || totalDirectoriesDiscovered <= completedDirectories) {
            return "N/A";
        }
        // Exponentially weighted moving average (recent samples weigh more)
        double weightedSpeed = 0;
        double weightSum = 0;
        int i = 0;
        for (EtaSample sample : etaWindow) {
            double weight = Math.pow(0.7, etaWindow.size() - 1 - i);
            weightedSpeed += sample.dirsPerSec * weight;
            weightSum += weight;
            i++;
        }
        if (weightSum <= 0) {
            return "N/A";
        }
        double avgSpeed = weightedSpeed / weightSum;
        if (avgSpeed <= 0) {
            return "N/A";
        }
        long remainingDirs = totalDirectoriesDiscovered - completedDirectories;
        long etaSec = (long) (remainingDirs / avgSpeed);
        return formatElapsed(etaSec * 1000);
    }

    // ════════════════════════════════════════════════════════
    // Log & persist triggers
    // ════════════════════════════════════════════════════════

    public boolean shouldLog() {
        long now = System.currentTimeMillis();
        boolean timeTriggered = (now - lastLogTimeMs) >= logIntervalSec * 1000L;
        boolean dirTriggered = (completedDirectories - lastLogDirCount) >= logIntervalDirs;
        if (timeTriggered || dirTriggered) {
            lastLogTimeMs = now;
            lastLogDirCount = completedDirectories;
            return true;
        }
        return false;
    }

    public boolean shouldPersistProgress() {
        long now = System.currentTimeMillis();
        boolean timeTriggered = (now - lastPersistTimeMs) >= persistIntervalSec * 1000L;
        boolean dirTriggered = (completedDirectories - lastPersistDirCount) >= persistIntervalDirs;
        if (timeTriggered || dirTriggered) {
            lastPersistTimeMs = now;
            lastPersistDirCount = completedDirectories;
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════
    // Progress logging
    // ════════════════════════════════════════════════════════

    public void logProgress() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        log.info("SCAN_PROGRESS phase={} dirs={}/{}({}) files={}/{} "
                        + "added={} updated={} skipped={} failed={} dirSkipped={} "
                        + "speed={} ETA={} elapsed={}",
                currentPhase.name(),
                completedDirectories, totalDirectoriesDiscovered, formatPercent(),
                filesProcessed, totalFilesDiscovered,
                filesAdded, filesUpdated, filesSkipped, filesFailed,
                skippedDirectories,
                formatSpeed(), formatSmoothedEta(), formatElapsed(elapsed));
    }

    public String formatPercent() {
        if (totalDirectoriesDiscovered <= 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", completedDirectories * 100.0 / totalDirectoriesDiscovered);
    }

    public int getProgressPercent() {
        if (totalDirectoriesDiscovered <= 0) {
            return 0;
        }
        return (int) (completedDirectories * 100L / totalDirectoriesDiscovered);
    }

    private String formatSpeed() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        if (elapsedMs <= 0) {
            return "0.0 dirs/s";
        }
        double dirsPerSec = completedDirectories * 1000.0 / elapsedMs;
        double filesPerSec = filesProcessed * 1000.0 / elapsedMs;
        return String.format(Locale.ROOT, "%.1f dirs/s, %.1f files/s", dirsPerSec, filesPerSec);
    }

    private String formatElapsed(long elapsedMs) {
        if (elapsedMs < 1000) {
            return elapsedMs + "ms";
        }
        long seconds = elapsedMs / 1000;
        long minutes = seconds / 60;
        long remainSeconds = seconds % 60;
        if (minutes <= 0) {
            return seconds + "s";
        }
        long hours = minutes / 60;
        long remainMinutes = minutes % 60;
        if (hours <= 0) {
            return minutes + "m" + remainSeconds + "s";
        }
        return hours + "h" + remainMinutes + "m" + remainSeconds + "s";
    }

    // ════════════════════════════════════════════════════════
    // Getters
    // ════════════════════════════════════════════════════════

    public int getTotalDirectoriesDiscovered() { return totalDirectoriesDiscovered; }
    public int getCompletedDirectories() { return completedDirectories; }
    public int getSkippedDirectories() { return skippedDirectories; }
    public int getTotalFilesDiscovered() { return totalFilesDiscovered; }
    public int getFilesProcessed() { return filesProcessed; }
    public int getFilesAdded() { return filesAdded; }
    public int getFilesUpdated() { return filesUpdated; }
    public int getFilesFailed() { return filesFailed; }
    public int getFilesSkipped() { return filesSkipped; }
    public String getLastSyncedDir() { return lastSyncedDir; }
    public Phase getCurrentPhase() { return currentPhase; }
    public long getStartTimeMs() { return startTimeMs; }

    // ════════════════════════════════════════════════════════
    // Inner types
    // ════════════════════════════════════════════════════════

    private static class EtaSample {
        final double dirsPerSec;

        EtaSample(double dirsPerSec) {
            this.dirsPerSec = dirsPerSec;
        }
    }
}
