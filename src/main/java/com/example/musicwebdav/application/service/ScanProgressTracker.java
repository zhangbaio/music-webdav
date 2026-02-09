package com.example.musicwebdav.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScanProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(ScanProgressTracker.class);

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

    private long lastLogTimeMs;
    private int lastLogDirCount;
    private long lastPersistTimeMs;
    private int lastPersistDirCount;

    private final int logIntervalSec;
    private final int logIntervalDirs;
    private final int persistIntervalSec;
    private final int persistIntervalDirs;

    public ScanProgressTracker(int logIntervalSec, int persistIntervalSec) {
        this.startTimeMs = System.currentTimeMillis();
        this.lastLogTimeMs = this.startTimeMs;
        this.lastPersistTimeMs = this.startTimeMs;
        this.logIntervalSec = logIntervalSec > 0 ? logIntervalSec : 30;
        this.logIntervalDirs = 10;
        this.persistIntervalSec = persistIntervalSec > 0 ? persistIntervalSec : 30;
        this.persistIntervalDirs = 5;
    }

    public void onDirectoryDiscovered(String dirPath, int fileCount) {
        totalDirectoriesDiscovered++;
        totalFilesDiscovered += fileCount;
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
    }

    public void onDirectorySkipped(String dirPath) {
        completedDirectories++;
        skippedDirectories++;
        lastSyncedDir = dirPath;
    }

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

    public void logProgress() {
        log.info("SCAN_PROGRESS dirs={}/{}({}) files={}/{} added={} updated={} skipped={} failed={} dirSkipped={} speed={} ETA={}",
                completedDirectories, totalDirectoriesDiscovered, formatPercent(),
                filesProcessed, totalFilesDiscovered,
                filesAdded, filesUpdated, filesSkipped, filesFailed,
                skippedDirectories, formatSpeed(), formatEta());
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
            return "0.0 files/s";
        }
        double filesPerSec = filesProcessed * 1000.0 / elapsedMs;
        return String.format("%.1f files/s", filesPerSec);
    }

    private String formatEta() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        if (completedDirectories <= 0 || totalDirectoriesDiscovered <= completedDirectories) {
            return "N/A";
        }
        long msPerDir = elapsedMs / completedDirectories;
        long remainingDirs = totalDirectoriesDiscovered - completedDirectories;
        long etaMs = remainingDirs * msPerDir;
        long etaSec = etaMs / 1000;
        if (etaSec < 60) {
            return etaSec + "s";
        }
        return (etaSec / 60) + "m" + (etaSec % 60) + "s";
    }

    // Getters
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
}
