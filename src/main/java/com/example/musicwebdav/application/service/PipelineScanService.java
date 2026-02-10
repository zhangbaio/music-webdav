package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppScanProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.common.util.HashUtil;
import com.example.musicwebdav.domain.model.AudioMetadata;
import com.example.musicwebdav.domain.model.WebDavDirectoryInfo;
import com.example.musicwebdav.domain.model.WebDavFileObject;
import com.example.musicwebdav.infrastructure.persistence.entity.DirectorySignatureEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.ScanCheckpointEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.DirectorySignatureMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanCheckpointMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanTaskMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanTaskSeenFileMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import com.example.musicwebdav.infrastructure.webdav.WebDavClient;
import com.github.sardine.Sardine;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PipelineScanService {

    private static final Logger log = LoggerFactory.getLogger(PipelineScanService.class);
    private static final Pattern MULTI_SLASH_PATTERN = Pattern.compile("/{2,}");

    private final WebDavClient webDavClient;
    private final TrackMapper trackMapper;
    private final ScanTaskSeenFileMapper scanTaskSeenFileMapper;
    private final ScanTaskMapper scanTaskMapper;
    private final DirectorySignatureMapper directorySignatureMapper;
    private final ScanCheckpointMapper scanCheckpointMapper;
    private final CoverArtDetector coverArtDetector;
    private final MetadataFallbackService metadataFallbackService;
    private final DuplicateFilterService duplicateFilterService;
    private final AppSecurityProperties appSecurityProperties;
    private final AppScanProperties appScanProperties;

    public PipelineScanService(WebDavClient webDavClient,
                                TrackMapper trackMapper,
                                ScanTaskSeenFileMapper scanTaskSeenFileMapper,
                                ScanTaskMapper scanTaskMapper,
                                DirectorySignatureMapper directorySignatureMapper,
                                ScanCheckpointMapper scanCheckpointMapper,
                                CoverArtDetector coverArtDetector,
                                MetadataFallbackService metadataFallbackService,
                                DuplicateFilterService duplicateFilterService,
                                AppSecurityProperties appSecurityProperties,
                                AppScanProperties appScanProperties) {
        this.webDavClient = webDavClient;
        this.trackMapper = trackMapper;
        this.scanTaskSeenFileMapper = scanTaskSeenFileMapper;
        this.scanTaskMapper = scanTaskMapper;
        this.directorySignatureMapper = directorySignatureMapper;
        this.scanCheckpointMapper = scanCheckpointMapper;
        this.coverArtDetector = coverArtDetector;
        this.metadataFallbackService = metadataFallbackService;
        this.duplicateFilterService = duplicateFilterService;
        this.appSecurityProperties = appSecurityProperties;
        this.appScanProperties = appScanProperties;
    }

    public ScanResult scan(Long taskId, WebDavConfigEntity config,
                            BooleanSupplier cancelSignal, Set<String> resumedCheckpoints) {
        ScanResult result = new ScanResult();
        String plainPassword = AesCryptoUtil.decrypt(config.getPasswordEnc(), appSecurityProperties.getEncryptKey());
        Set<String> supportedExtensions = appScanProperties.normalizedAudioExtensions();
        Set<String> lyricExtensions = appScanProperties.normalizedLyricExtensions();
        if (supportedExtensions.isEmpty()) {
            throw new IllegalStateException("app.scan.audio-extensions 配置为空");
        }

        ScanProgressTracker tracker = new ScanProgressTracker(30, appScanProperties.getProgressPersistIntervalSec());
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());

        log.info("PIPELINE_SCAN_START taskId={} configId={} configName={} rootUrl={}",
                taskId, config.getId(), config.getName(), rootUrl);
        log.info("PIPELINE_SCAN_METADATA_MODE taskId={} mode=WEBDAV_INFER_ONLY", taskId);
        // Current API only exposes FULL scan. To avoid false negatives ("full scan but no writes"),
        // disable directory-signature short-circuit and always process directories.
        final boolean directorySkipEnabled = false;
        log.info("PIPELINE_SCAN_DIRECTORY_SKIP taskId={} enabled={}", taskId, directorySkipEnabled);

        Sardine session = webDavClient.createSession(config.getUsername(), plainPassword);
        try {
            Deque<String> dirQueue = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            dirQueue.push(rootUrl);
            tracker.addDiscoveredDirectories(1);

            while (!dirQueue.isEmpty()) {
                if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                    result.setCanceled(true);
                    log.info("PIPELINE_SCAN_CANCELED taskId={}", taskId);
                    break;
                }

                String dirUrl = dirQueue.pop();
                String dirKey = normalizeUrl(dirUrl);
                if (!visited.add(dirKey)) {
                    continue;
                }

                WebDavDirectoryInfo dirInfo;
                try {
                    dirInfo = webDavClient.listDirectory(session, dirUrl, rootUrl);
                } catch (Exception e) {
                    log.warn("PIPELINE_SCAN_DIR_ERROR taskId={} dirUrl={} error={}", taskId, dirUrl, e.getMessage());
                    String failedRelPath = dirUrl.startsWith(rootUrl) ? dirUrl.substring(rootUrl.length()) : dirUrl;
                    String failedPathMd5 = HashUtil.md5Hex(safeRelativePath(failedRelPath));
                    saveCheckpoint(taskId, failedRelPath, failedPathMd5, "FAILED", 0, 0, 1, e.getMessage());
                    result.incrementFailedCount();
                    continue;
                }

                // Enqueue subdirectories
                for (String subdir : dirInfo.getSubdirectoryUrls()) {
                    dirQueue.push(subdir);
                }
                tracker.addDiscoveredDirectories(dirInfo.getSubdirectoryUrls().size());
                tracker.onDirectoryDiscovered(dirInfo.getRelativePath(), dirInfo.getFiles().size());

                String dirPathMd5 = HashUtil.md5Hex(safeRelativePath(dirInfo.getRelativePath()));

                // Check resume checkpoint
                if (resumedCheckpoints != null && resumedCheckpoints.contains(dirPathMd5)) {
                    recordSeenFilesForDirectory(taskId, dirInfo, supportedExtensions);
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    logIfNeeded(tracker);
                    continue;
                }

                // Check directory signature (incremental)
                if (directorySkipEnabled && isDirectoryUnchanged(config.getId(), dirInfo, dirPathMd5)) {
                    recordSeenFilesForDirectory(taskId, dirInfo, supportedExtensions);
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    logIfNeeded(tracker);
                    continue;
                }

                // Detect cover art
                String coverUrl = coverArtDetector.detectCoverInDirectory(dirInfo.getFiles());

                // Process files in this directory
                DirProcessResult dirResult = processDirectoryFiles(
                        taskId, config, dirInfo, coverUrl, supportedExtensions, lyricExtensions);
                result.addDirResult(dirResult);

                // Update directory signature
                updateDirectorySignature(config.getId(), dirInfo, dirPathMd5);

                // Save checkpoint
                saveCheckpoint(taskId, dirInfo.getRelativePath(), dirPathMd5,
                        dirResult.failed > 0 ? "FAILED" : "COMPLETED",
                        dirInfo.getFiles().size(), dirResult.processed, dirResult.failed, null);

                tracker.onDirectoryCompleted(dirInfo.getRelativePath(),
                        dirResult.processed, dirResult.added, dirResult.updated,
                        dirResult.skipped, dirResult.failed);

                // Persist progress
                if (tracker.shouldPersistProgress()) {
                    persistProgress(taskId, result, tracker);
                }

                logIfNeeded(tracker);
            }

            // Post-scan: soft-delete + dedup
            if (!result.isCanceled()) {
                int deleted = trackMapper.softDeleteByTaskId(taskId, config.getId());
                result.setDeletedCount(deleted);
                int deduped = duplicateFilterService.deduplicateTracks(config.getId());
                result.setDeduplicatedCount(deduped);
            }

            // Final progress persist
            persistProgress(taskId, result, tracker);

        } finally {
            webDavClient.closeSession(session);
        }

        log.info("PIPELINE_SCAN_FINISH taskId={} totalFiles={} audioFiles={} added={} updated={} deleted={} "
                + "deduped={} failed={} dirsTotal={} dirsSkipped={}",
                taskId, result.getTotalFiles(), result.getAudioFiles(), result.getAddedCount(),
                result.getUpdatedCount(), result.getDeletedCount(), result.getDeduplicatedCount(),
                result.getFailedCount(), tracker.getTotalDirectoriesDiscovered(),
                tracker.getSkippedDirectories());
        return result;
    }

    private DirProcessResult processDirectoryFiles(Long taskId, WebDavConfigEntity config,
                                                     WebDavDirectoryInfo dirInfo,
                                                     String coverUrl, Set<String> supportedExtensions,
                                                     Set<String> lyricExtensions) {
        DirProcessResult dirResult = new DirProcessResult();
        List<TrackEntity> trackBatch = new ArrayList<>();
        List<String> seenMd5Batch = new ArrayList<>();
        Map<String, String> lyricPathIndex = buildLyricPathIndex(dirInfo.getFiles(), lyricExtensions);
        int dbBatchSize = Math.max(10, appScanProperties.getDbBatchSize());

        for (WebDavFileObject file : dirInfo.getFiles()) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }
            String pathMd5 = HashUtil.md5Hex(relativePath);
            seenMd5Batch.add(pathMd5);

            if (!isAudioFile(relativePath, supportedExtensions)) {
                dirResult.skipped++;
                continue;
            }

            dirResult.audioFiles++;

            try {
                TrackEntity existing = trackMapper.selectByConfigAndPathMd5(config.getId(), pathMd5);
                // Pure WebDAV infer mode may evolve over time; recompute metadata from path/dir and
                // upsert when inferred fields differ, even if file fingerprint is unchanged.
                AudioMetadata metadata = new AudioMetadata();
                String lyricPath = resolveLyricPath(relativePath, lyricPathIndex);
                TrackEntity entity = buildTrackEntity(taskId, config.getId(), relativePath, pathMd5,
                        file, metadata, coverUrl, lyricPath);
                if (sameFingerprint(existing, file) && sameTrackMetadata(existing, entity)) {
                    dirResult.skipped++;
                    continue;
                }

                trackBatch.add(entity);

                if (existing == null) {
                    dirResult.added++;
                } else {
                    dirResult.updated++;
                }
                dirResult.processed++;

                // Flush batch if needed
                if (trackBatch.size() >= dbBatchSize) {
                    flushTrackBatch(trackBatch);
                }
            } catch (Exception e) {
                dirResult.failed++;
                dirResult.processed++;
                log.warn("Scan file failed, taskId={}, path={}", taskId, relativePath, e);
            }
        }

        // Flush remaining tracks
        if (!trackBatch.isEmpty()) {
            flushTrackBatch(trackBatch);
        }

        // Batch insert seen files
        if (!seenMd5Batch.isEmpty()) {
            batchInsertSeenFiles(taskId, seenMd5Batch, dbBatchSize);
        }

        return dirResult;
    }

    private void flushTrackBatch(List<TrackEntity> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            trackMapper.batchUpsert(batch);
        } catch (Exception e) {
            log.warn("Batch upsert failed, falling back to individual inserts", e);
            for (TrackEntity entity : batch) {
                try {
                    trackMapper.upsert(entity);
                } catch (Exception ex) {
                    log.warn("Individual upsert failed: path={}", entity.getSourcePath(), ex);
                }
            }
        }
        batch.clear();
    }

    private void batchInsertSeenFiles(Long taskId, List<String> md5List, int batchSize) {
        for (int i = 0; i < md5List.size(); i += batchSize) {
            int end = Math.min(i + batchSize, md5List.size());
            List<String> subList = md5List.subList(i, end);
            try {
                scanTaskSeenFileMapper.batchInsert(taskId, subList);
            } catch (Exception e) {
                log.warn("Batch insert seen files failed, falling back to individual inserts", e);
                for (String md5 : subList) {
                    scanTaskSeenFileMapper.insert(taskId, md5);
                }
            }
        }
    }

    private void recordSeenFilesForDirectory(Long taskId, WebDavDirectoryInfo dirInfo,
                                               Set<String> supportedExtensions) {
        List<String> md5List = new ArrayList<>();
        for (WebDavFileObject file : dirInfo.getFiles()) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (StringUtils.hasText(relativePath)) {
                md5List.add(HashUtil.md5Hex(relativePath));
            }
        }
        if (!md5List.isEmpty()) {
            batchInsertSeenFiles(taskId, md5List, Math.max(10, appScanProperties.getDbBatchSize()));
        }
    }

    private boolean isDirectoryUnchanged(Long configId, WebDavDirectoryInfo dirInfo, String dirPathMd5) {
        DirectorySignatureEntity existing = directorySignatureMapper.selectByConfigAndDirPathMd5(configId, dirPathMd5);
        if (existing == null) {
            return false;
        }
        // Compare etag
        if (StringUtils.hasText(existing.getDirEtag()) && StringUtils.hasText(dirInfo.getEtag())) {
            if (!existing.getDirEtag().equals(dirInfo.getEtag())) {
                return false;
            }
        }
        // Compare lastModified
        if (existing.getDirLastModified() != null && dirInfo.getLastModified() != null) {
            LocalDateTime infoLm = LocalDateTime.ofInstant(dirInfo.getLastModified().toInstant(), ZoneId.systemDefault());
            if (!existing.getDirLastModified().equals(infoLm)) {
                return false;
            }
        }
        // Compare child count
        if (existing.getChildCount() != null && existing.getChildCount() != dirInfo.getChildCount()) {
            return false;
        }
        return true;
    }

    private void updateDirectorySignature(Long configId, WebDavDirectoryInfo dirInfo, String dirPathMd5) {
        DirectorySignatureEntity entity = new DirectorySignatureEntity();
        entity.setConfigId(configId);
        entity.setDirPath(safeRelativePath(dirInfo.getRelativePath()));
        entity.setDirPathMd5(dirPathMd5);
        entity.setDirEtag(dirInfo.getEtag());
        if (dirInfo.getLastModified() != null) {
            entity.setDirLastModified(LocalDateTime.ofInstant(dirInfo.getLastModified().toInstant(), ZoneId.systemDefault()));
        }
        entity.setChildCount(dirInfo.getChildCount());
        directorySignatureMapper.upsert(entity);
    }

    private void saveCheckpoint(Long taskId, String dirRelativePath, String dirPathMd5,
                                 String status, int fileCount, int processedCount, int failedCount,
                                 String errorMessage) {
        ScanCheckpointEntity checkpoint = new ScanCheckpointEntity();
        checkpoint.setTaskId(taskId);
        checkpoint.setDirPath(dirRelativePath);
        checkpoint.setDirPathMd5(dirPathMd5);
        checkpoint.setStatus(status);
        checkpoint.setFileCount(fileCount);
        checkpoint.setProcessedCount(processedCount);
        checkpoint.setFailedCount(failedCount);
        checkpoint.setErrorMessage(limitLength(errorMessage, 1000));
        scanCheckpointMapper.upsert(checkpoint);
    }


    private void persistProgress(Long taskId, ScanResult result, ScanProgressTracker tracker) {
        try {
            scanTaskMapper.updateProgress(
                    taskId,
                    result.getTotalFiles(),
                    result.getAudioFiles(),
                    result.getAddedCount(),
                    result.getUpdatedCount(),
                    result.getFailedCount(),
                    tracker.getCompletedDirectories(),
                    tracker.getTotalDirectoriesDiscovered(),
                    tracker.getLastSyncedDir(),
                    tracker.getProgressPercent());
        } catch (Exception e) {
            log.warn("Failed to persist scan progress, taskId={}", taskId, e);
        }
    }

    private void logIfNeeded(ScanProgressTracker tracker) {
        if (tracker.shouldLog()) {
            tracker.logProgress();
        }
    }

    private TrackEntity buildTrackEntity(Long taskId, Long configId, String relativePath,
                                          String pathMd5, WebDavFileObject file,
                                          AudioMetadata metadata, String coverUrl, String lyricPath) {
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
        entity.setMimeType(normalizeMimeType(file.getMimeType()));
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
        if (entity.getHasCover() == 0 && coverUrl != null) {
            entity.setCoverArtUrl(coverUrl);
        }
        entity.setHasLyric(StringUtils.hasText(lyricPath) ? 1 : 0);
        entity.setLyricPath(StringUtils.hasText(lyricPath) ? lyricPath : null);
        entity.setLastScanTaskId(taskId);
        return entity;
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

    private boolean sameTrackMetadata(TrackEntity existing, TrackEntity candidate) {
        if (existing == null || candidate == null) {
            return false;
        }
        return sameText(existing.getTitle(), candidate.getTitle())
                && sameText(existing.getArtist(), candidate.getArtist())
                && sameText(existing.getAlbum(), candidate.getAlbum())
                && sameText(existing.getAlbumArtist(), candidate.getAlbumArtist())
                && sameText(existing.getGenre(), candidate.getGenre())
                && sameText(existing.getMimeType(), candidate.getMimeType())
                && sameText(existing.getCoverArtUrl(), candidate.getCoverArtUrl())
                && sameText(existing.getLyricPath(), candidate.getLyricPath())
                && Objects.equals(existing.getTrackNo(), candidate.getTrackNo())
                && Objects.equals(existing.getDiscNo(), candidate.getDiscNo())
                && Objects.equals(existing.getYear(), candidate.getYear())
                && Objects.equals(existing.getDurationSec(), candidate.getDurationSec())
                && Objects.equals(existing.getBitrate(), candidate.getBitrate())
                && Objects.equals(existing.getSampleRate(), candidate.getSampleRate())
                && Objects.equals(existing.getChannels(), candidate.getChannels())
                && Objects.equals(existing.getHasCover(), candidate.getHasCover())
                && Objects.equals(existing.getHasLyric(), candidate.getHasLyric());
    }

    private boolean sameText(String left, String right) {
        return normalizeText(left).equals(normalizeText(right));
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String normalizeRelativePath(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        normalized = MULTI_SLASH_PATTERN.matcher(normalized).replaceAll("/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        String normalized = mimeType.trim();
        int semicolonIndex = normalized.indexOf(';');
        if (semicolonIndex > 0) {
            normalized = normalized.substring(0, semicolonIndex);
        }
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String safeRelativePath(String path) {
        return path == null ? "" : path;
    }

    private boolean isAudioFile(String relativePath, Set<String> supportedExtensions) {
        String ext = extractFileExtension(relativePath);
        if (!StringUtils.hasText(ext)) {
            return false;
        }
        return supportedExtensions.contains(ext);
    }

    private Map<String, String> buildLyricPathIndex(List<WebDavFileObject> files, Set<String> lyricExtensions) {
        Map<String, String> lyricPathIndex = new HashMap<>();
        if (lyricExtensions == null || lyricExtensions.isEmpty()) {
            return lyricPathIndex;
        }
        for (WebDavFileObject file : files) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }
            String extension = extractFileExtension(relativePath);
            if (!StringUtils.hasText(extension) || !lyricExtensions.contains(extension)) {
                continue;
            }
            String lyricKey = buildPathWithoutExtensionKey(relativePath);
            if (!StringUtils.hasText(lyricKey)) {
                continue;
            }
            lyricPathIndex.putIfAbsent(lyricKey, relativePath);
        }
        return lyricPathIndex;
    }

    private String resolveLyricPath(String audioRelativePath, Map<String, String> lyricPathIndex) {
        if (!StringUtils.hasText(audioRelativePath) || lyricPathIndex == null || lyricPathIndex.isEmpty()) {
            return null;
        }
        String lyricKey = buildPathWithoutExtensionKey(audioRelativePath);
        if (!StringUtils.hasText(lyricKey)) {
            return null;
        }
        return lyricPathIndex.get(lyricKey);
    }

    private String buildPathWithoutExtensionKey(String relativePath) {
        String basePath = stripFileExtension(relativePath);
        if (!StringUtils.hasText(basePath)) {
            return null;
        }
        return basePath.toLowerCase(Locale.ROOT);
    }

    private String stripFileExtension(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        int idx = relativePath.lastIndexOf('.');
        if (idx < 0) {
            return relativePath;
        }
        if (idx == 0) {
            return null;
        }
        return relativePath.substring(0, idx);
    }

    private String extractFileExtension(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return null;
        }
        int idx = relativePath.lastIndexOf('.');
        if (idx < 0 || idx >= relativePath.length() - 1) {
            return null;
        }
        return relativePath.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String normalized = url;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String limitLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    // --- Inner classes ---

    static class DirProcessResult {
        int processed;
        int added;
        int updated;
        int skipped;
        int failed;
        int audioFiles;
    }

    public static class ScanResult {
        private int totalFiles;
        private int audioFiles;
        private int addedCount;
        private int updatedCount;
        private int deletedCount;
        private int failedCount;
        private int deduplicatedCount;
        private boolean canceled;

        public void addDirResult(DirProcessResult dir) {
            totalFiles += dir.processed + dir.skipped;
            audioFiles += dir.audioFiles;
            addedCount += dir.added;
            updatedCount += dir.updated;
            failedCount += dir.failed;
        }

        public void incrementFailedCount() { failedCount++; }

        public int getTotalFiles() { return totalFiles; }
        public int getAudioFiles() { return audioFiles; }
        public int getAddedCount() { return addedCount; }
        public int getUpdatedCount() { return updatedCount; }
        public int getDeletedCount() { return deletedCount; }
        public void setDeletedCount(int deletedCount) { this.deletedCount = deletedCount; }
        public int getFailedCount() { return failedCount; }
        public int getDeduplicatedCount() { return deduplicatedCount; }
        public void setDeduplicatedCount(int deduplicatedCount) { this.deduplicatedCount = deduplicatedCount; }
        public boolean isCanceled() { return canceled; }
        public void setCanceled(boolean canceled) { this.canceled = canceled; }
    }
}
