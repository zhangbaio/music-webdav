package com.example.musicwebdav.application.service;

import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.domain.enumtype.TaskType;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FullScanService {

    private static final Logger log = LoggerFactory.getLogger(FullScanService.class);

    private final PipelineScanService pipelineScanService;

    public FullScanService(PipelineScanService pipelineScanService) {
        this.pipelineScanService = pipelineScanService;
    }

    /**
     * Execute a full scan using the pipeline architecture.
     *
     * @param taskId            the scan task ID
     * @param config            the WebDAV config entity
     * @param cancelSignal      signal to check for cancellation
     * @param resumedCheckpoints set of dir path MD5s from a previous task to skip (for resume)
     * @return scan statistics
     */
    public ScanStats scan(Long taskId, TaskType taskType, WebDavConfigEntity config,
                           BooleanSupplier cancelSignal, Set<String> resumedCheckpoints) {
        PipelineScanService.ScanResult result = pipelineScanService.scan(
                taskId, taskType, config, cancelSignal, resumedCheckpoints);
        return toScanStats(result);
    }

    private ScanStats toScanStats(PipelineScanService.ScanResult result) {
        ScanStats stats = new ScanStats();
        stats.setTotalFiles(result.getTotalFiles());
        stats.setAudioFiles(result.getAudioFiles());
        stats.setAddedCount(result.getAddedCount());
        stats.setUpdatedCount(result.getUpdatedCount());
        stats.setDeletedCount(result.getDeletedCount());
        stats.setFailedCount(result.getFailedCount());
        stats.setDeduplicatedCount(result.getDeduplicatedCount());
        stats.setCanceled(result.isCanceled());
        return stats;
    }

    public static class ScanStats {

        private int totalFiles;
        private int audioFiles;
        private int addedCount;
        private int updatedCount;
        private int deletedCount;
        private int failedCount;
        private int deduplicatedCount;
        private boolean canceled;

        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
        public int getAudioFiles() { return audioFiles; }
        public void setAudioFiles(int audioFiles) { this.audioFiles = audioFiles; }
        public int getAddedCount() { return addedCount; }
        public void setAddedCount(int addedCount) { this.addedCount = addedCount; }
        public int getUpdatedCount() { return updatedCount; }
        public void setUpdatedCount(int updatedCount) { this.updatedCount = updatedCount; }
        public int getDeletedCount() { return deletedCount; }
        public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }
        public int getFailedCount() { return failedCount; }
        public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
        public int getDeduplicatedCount() { return deduplicatedCount; }
        public void setDeduplicatedCount(int deduplicatedCount) { this.deduplicatedCount = deduplicatedCount; }
        public boolean isCanceled() { return canceled; }
        public void setCanceled(boolean canceled) { this.canceled = canceled; }
    }
}
