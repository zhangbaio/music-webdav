package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppScanProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.common.util.HashUtil;
import com.example.musicwebdav.domain.model.AudioMetadata;
import com.example.musicwebdav.domain.model.WebDavFileObject;
import com.example.musicwebdav.infrastructure.parser.AudioMetadataParser;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanTaskItemMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanTaskSeenFileMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FullScanService {

    private static final Logger log = LoggerFactory.getLogger(FullScanService.class);

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final WebDavClient webDavClient;
    private final AudioMetadataParser audioMetadataParser;
    private final TrackMapper trackMapper;
    private final ScanTaskItemMapper scanTaskItemMapper;
    private final ScanTaskSeenFileMapper scanTaskSeenFileMapper;
    private final AppSecurityProperties appSecurityProperties;
    private final AppScanProperties appScanProperties;
    private final MetadataFallbackService metadataFallbackService;

    public FullScanService(WebDavClient webDavClient,
                           AudioMetadataParser audioMetadataParser,
                           TrackMapper trackMapper,
                           ScanTaskItemMapper scanTaskItemMapper,
                           ScanTaskSeenFileMapper scanTaskSeenFileMapper,
                           AppSecurityProperties appSecurityProperties,
                           AppScanProperties appScanProperties,
                           MetadataFallbackService metadataFallbackService) {
        this.webDavClient = webDavClient;
        this.audioMetadataParser = audioMetadataParser;
        this.trackMapper = trackMapper;
        this.scanTaskItemMapper = scanTaskItemMapper;
        this.scanTaskSeenFileMapper = scanTaskSeenFileMapper;
        this.appSecurityProperties = appSecurityProperties;
        this.appScanProperties = appScanProperties;
        this.metadataFallbackService = metadataFallbackService;
    }

    public ScanStats scan(Long taskId, WebDavConfigEntity config, BooleanSupplier cancelSignal) {
        ScanStats stats = new ScanStats();
        String plainPassword = AesCryptoUtil.decrypt(config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
        Set<String> supportedExtensions = appScanProperties.normalizedAudioExtensions();
        if (supportedExtensions.isEmpty()) {
            throw new IllegalStateException("app.scan.audio-extensions 配置为空");
        }

        log.info("FULL_SCAN_START taskId={} configId={} configName={} rootPath={} extensions={}",
                taskId, config.getId(), config.getName(), config.getRootPath(), supportedExtensions);

        List<WebDavFileObject> files = webDavClient.listFiles(
                config.getBaseUrl(),
                config.getUsername(),
                plainPassword,
                config.getRootPath());
        stats.setTotalFiles(files.size());
        log.info("FULL_SCAN_DISCOVERED taskId={} totalFiles={}", taskId, files.size());

        int processedCount = 0;
        int skippedCount = 0;
        for (WebDavFileObject file : files) {
            if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                stats.setCanceled(true);
                log.info("FULL_SCAN_CANCELED taskId={} processed={} total={}", taskId, processedCount, stats.getTotalFiles());
                break;
            }
            processedCount++;
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }
            String pathMd5 = HashUtil.md5Hex(relativePath);
            scanTaskSeenFileMapper.insert(taskId, pathMd5);

            if (!isAudioFile(relativePath, supportedExtensions)) {
                scanTaskItemMapper.insert(taskId, relativePath, pathMd5, STATUS_SKIPPED, null, null);
                skippedCount++;
                continue;
            }

            stats.setAudioFiles(stats.getAudioFiles() + 1);

            try {
                TrackEntity existing = trackMapper.selectByConfigAndPathMd5(config.getId(), pathMd5);
                if (sameFingerprint(existing, file)) {
                    scanTaskItemMapper.insert(taskId, relativePath, pathMd5, STATUS_SKIPPED, null, "文件未变化");
                    skippedCount++;
                    continue;
                }

                File tempFile = downloadWithRetry(config.getUsername(), plainPassword, file, taskId, relativePath);
                AudioMetadata metadata;
                boolean metadataFallback = false;
                String parseWarnMessage = null;
                try {
                    metadata = audioMetadataParser.parse(tempFile);
                } catch (Exception parseEx) {
                    metadata = new AudioMetadata();
                    metadataFallback = true;
                    parseWarnMessage = limitLength(parseEx.getClass().getSimpleName() + ": " + parseEx.getMessage(), 1000);
                    log.warn("Metadata parse fallback, taskId={}, path={}, reason={}",
                            taskId, relativePath, parseWarnMessage);
                } finally {
                    if (!tempFile.delete()) {
                        log.debug("Temp file delete failed: {}", tempFile.getAbsolutePath());
                    }
                }

                TrackEntity entity = buildTrackEntity(taskId, config.getId(), relativePath, pathMd5, file, metadata);
                trackMapper.upsert(entity);

                if (existing == null) {
                    stats.setAddedCount(stats.getAddedCount() + 1);
                } else {
                    stats.setUpdatedCount(stats.getUpdatedCount() + 1);
                }
                if (metadataFallback) {
                    scanTaskItemMapper.insert(taskId, relativePath, pathMd5, STATUS_SUCCESS, "METADATA_FALLBACK", parseWarnMessage);
                } else {
                    scanTaskItemMapper.insert(taskId, relativePath, pathMd5, STATUS_SUCCESS, null, null);
                }
            } catch (Exception e) {
                stats.setFailedCount(stats.getFailedCount() + 1);
                String errorMessage = limitLength(e.getMessage(), 1000);
                scanTaskItemMapper.insert(taskId, relativePath, pathMd5, STATUS_FAILED, "SCAN_FILE_FAILED", errorMessage);
                log.warn("Scan file failed, taskId={}, path={}", taskId, relativePath, e);
            }

            if (processedCount % 200 == 0) {
                log.info("FULL_SCAN_PROGRESS taskId={} processed={} total={} audio={} added={} updated={} failed={}",
                        taskId, processedCount, stats.getTotalFiles(), stats.getAudioFiles(),
                        stats.getAddedCount(), stats.getUpdatedCount(), stats.getFailedCount());
            }
        }

        if (!stats.isCanceled()) {
            int deleted = trackMapper.softDeleteByTaskId(taskId, config.getId());
            stats.setDeletedCount(deleted);
        }
        log.info("FULL_SCAN_FINISH taskId={} total={} audio={} added={} updated={} deleted={} failed={} skipped={}",
                taskId, stats.getTotalFiles(), stats.getAudioFiles(), stats.getAddedCount(),
                stats.getUpdatedCount(), stats.getDeletedCount(), stats.getFailedCount(), skippedCount);
        return stats;
    }

    private File downloadWithRetry(String username,
                                   String password,
                                   WebDavFileObject file,
                                   Long taskId,
                                   String relativePath) throws Exception {
        int maxAttempts = Math.max(1, appScanProperties.getMaxRetry());
        long expectedSize = file.getSize() == null ? -1L : file.getSize();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            File tempFile = null;
            try {
                tempFile = webDavClient.downloadToTempFile(username, password, file.getFileUrl());
                long actualSize = tempFile.length();
                if (actualSize <= 0) {
                    throw new IllegalStateException("下载文件为空");
                }
                if (expectedSize > 0 && actualSize != expectedSize) {
                    throw new IllegalStateException("下载文件大小不匹配，expected=" + expectedSize + ", actual=" + actualSize);
                }
                return tempFile;
            } catch (Exception e) {
                lastError = e;
                if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                    log.debug("Temp file delete failed after download error: {}", tempFile.getAbsolutePath());
                }
                log.warn("Download attempt failed, taskId={}, path={}, attempt={}/{}, reason={}",
                        taskId, relativePath, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleepRetryBackoff(attempt);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("下载文件失败") : lastError;
    }

    private void sleepRetryBackoff(int attempt) {
        long backoff = Math.max(100, appScanProperties.getRetryBackoffMs());
        long sleepMs = backoff * attempt;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载重试被中断");
        }
    }

    private String normalizeRelativePath(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private boolean isAudioFile(String relativePath, Set<String> supportedExtensions) {
        int idx = relativePath.lastIndexOf('.');
        if (idx < 0 || idx >= relativePath.length() - 1) {
            return false;
        }
        String ext = relativePath.substring(idx + 1).toLowerCase(Locale.ROOT);
        return supportedExtensions.contains(ext);
    }

    private boolean sameFingerprint(TrackEntity existing, WebDavFileObject file) {
        if (existing == null) {
            return false;
        }

        if (StringUtils.hasText(existing.getSourceEtag()) && StringUtils.hasText(file.getEtag())) {
            return existing.getSourceEtag().equals(file.getEtag());
        }

        if (existing.getSourceLastModified() != null && file.getLastModified() != null
                && existing.getSourceSize() != null && file.getSize() != null) {
            LocalDateTime lastModified = LocalDateTime.ofInstant(file.getLastModified().toInstant(), ZoneId.systemDefault());
            return existing.getSourceLastModified().equals(lastModified)
                    && existing.getSourceSize().equals(file.getSize());
        }
        return false;
    }

    private TrackEntity buildTrackEntity(Long taskId,
                                         Long configId,
                                         String relativePath,
                                         String pathMd5,
                                         WebDavFileObject file,
                                         AudioMetadata metadata) {
        AudioMetadata safeMetadata = metadataFallbackService.applyFallback(metadata, relativePath);
        TrackEntity entity = new TrackEntity();
        entity.setSourceConfigId(configId);
        entity.setSourcePath(relativePath);
        entity.setSourcePathMd5(pathMd5);
        entity.setSourceEtag(file.getEtag());
        if (file.getLastModified() != null) {
            entity.setSourceLastModified(LocalDateTime.ofInstant(file.getLastModified().toInstant(), ZoneId.systemDefault()));
        }
        entity.setSourceSize(file.getSize());
        entity.setMimeType(file.getMimeType());
        entity.setContentHash(null);
        entity.setTitle(safeMetadata.getTitle());
        entity.setArtist(safeMetadata.getArtist());
        entity.setAlbum(safeMetadata.getAlbum());
        entity.setAlbumArtist(safeMetadata.getAlbumArtist());
        entity.setTrackNo(safeMetadata.getTrackNo());
        entity.setDiscNo(safeMetadata.getDiscNo());
        entity.setYear(safeMetadata.getYear());
        entity.setGenre(safeMetadata.getGenre());
        entity.setDurationSec(safeMetadata.getDurationSec());
        entity.setBitrate(safeMetadata.getBitrate());
        entity.setSampleRate(safeMetadata.getSampleRate());
        entity.setChannels(safeMetadata.getChannels());
        entity.setHasCover(Boolean.TRUE.equals(safeMetadata.getHasCover()) ? 1 : 0);
        entity.setLastScanTaskId(taskId);
        return entity;
    }

    private String limitLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public static class ScanStats {

        private int totalFiles;
        private int audioFiles;
        private int addedCount;
        private int updatedCount;
        private int deletedCount;
        private int failedCount;
        private boolean canceled;

        public int getTotalFiles() {
            return totalFiles;
        }

        public void setTotalFiles(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        public int getAudioFiles() {
            return audioFiles;
        }

        public void setAudioFiles(int audioFiles) {
            this.audioFiles = audioFiles;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public void setAddedCount(int addedCount) {
            this.addedCount = addedCount;
        }

        public int getUpdatedCount() {
            return updatedCount;
        }

        public void setUpdatedCount(int updatedCount) {
            this.updatedCount = updatedCount;
        }

        public int getDeletedCount() {
            return deletedCount;
        }

        public void setDeletedCount(int deletedCount) {
            this.deletedCount = deletedCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }
}
