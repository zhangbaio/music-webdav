package com.example.musicwebdav.application.service;

import com.example.musicwebdav.common.config.AppScanProperties;
import com.example.musicwebdav.common.config.AppSecurityProperties;
import com.example.musicwebdav.common.util.AesCryptoUtil;
import com.example.musicwebdav.common.util.HashUtil;
import com.example.musicwebdav.domain.enumtype.TaskType;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Date;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private final MeterRegistry meterRegistry;

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
                                AppScanProperties appScanProperties,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
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
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public ScanResult scan(Long taskId, TaskType taskType, WebDavConfigEntity config,
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
        boolean isIncremental = TaskType.INCREMENTAL == taskType;
        final boolean directorySkipEnabled = isIncremental
                ? appScanProperties.isIncrementalDirectorySkipEnabled()
                : appScanProperties.isFullDirectorySkipEnabled();
        final boolean deleteDetectionEnabled = !isIncremental || appScanProperties.isIncrementalEnableDeleteDetection();
        final boolean dedupEnabled = !isIncremental || appScanProperties.isIncrementalEnableDedup();
        final boolean hasResumeCheckpoints = resumedCheckpoints != null && !resumedCheckpoints.isEmpty();
        // When directories may be skipped (resume or signature-skip), deletion needs seen-file fallback.
        final boolean useSeenBasedDelete = deleteDetectionEnabled && (hasResumeCheckpoints || directorySkipEnabled);
        final int directoryProcessThreadCount = Math.max(1, appScanProperties.getDirectoryProcessThreadCount());
        final int directoryProcessMaxInFlight = Math.max(directoryProcessThreadCount,
                appScanProperties.getDirectoryProcessMaxInFlight());

        log.info("PIPELINE_SCAN_SWITCHES taskId={} taskType={} directorySkip={} deleteDetection={} dedup={} seenDelete={}",
                taskId, taskType.name(), directorySkipEnabled, deleteDetectionEnabled, dedupEnabled, useSeenBasedDelete);
        log.info("PIPELINE_SCAN_PARALLEL taskId={} directoryWorkers={} maxInFlight={}",
                taskId, directoryProcessThreadCount, directoryProcessMaxInFlight);
        incrementCounter("music.scan.task.started", 1, "task_type", taskType.name());

        String metricStatus = "SUCCESS";
        long taskStartNanos = System.nanoTime();
        Sardine session = webDavClient.createSession(config.getUsername(), plainPassword);
        ExecutorService directoryExecutor = Executors.newFixedThreadPool(
                directoryProcessThreadCount, new NamedThreadFactory("scan-dir-"));
        CompletionService<DirectoryTaskOutcome> completionService =
                new ExecutorCompletionService<>(directoryExecutor);
        int inFlight = 0;
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
                long listStartNanos = System.nanoTime();
                try {
                    dirInfo = webDavClient.listDirectory(session, dirUrl, rootUrl);
                } catch (Exception e) {
                    recordDuration("music.scan.webdav.list_dir.duration", System.nanoTime() - listStartNanos,
                            "task_type", taskType.name(), "result", "ERROR");
                    log.warn("PIPELINE_SCAN_DIR_ERROR taskId={} dirUrl={} error={}", taskId, dirUrl, e.getMessage());
                    String failedRelPath = dirUrl.startsWith(rootUrl) ? dirUrl.substring(rootUrl.length()) : dirUrl;
                    String failedPathMd5 = HashUtil.md5Hex(safeRelativePath(failedRelPath));
                    saveCheckpoint(taskId, failedRelPath, failedPathMd5, "FAILED", 0, 0, 1, e.getMessage());
                    result.incrementFailedCount();
                    incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "LIST");
                    continue;
                }
                recordDuration("music.scan.webdav.list_dir.duration", System.nanoTime() - listStartNanos,
                        "task_type", taskType.name(), "result", "OK");

                // Enqueue subdirectories
                for (String subdir : dirInfo.getSubdirectoryUrls()) {
                    dirQueue.push(subdir);
                }
                tracker.addDiscoveredDirectories(dirInfo.getSubdirectoryUrls().size());
                tracker.onDirectoryDiscovered(dirInfo.getRelativePath(), dirInfo.getFiles().size());

                String dirPathMd5 = HashUtil.md5Hex(safeRelativePath(dirInfo.getRelativePath()));

                // Check resume checkpoint
                if (resumedCheckpoints != null && resumedCheckpoints.contains(dirPathMd5)) {
                    if (useSeenBasedDelete) {
                        recordSeenFilesForDirectory(taskId, dirInfo, supportedExtensions);
                    }
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    incrementCounter("music.scan.dir.skipped", 1,
                            "task_type", taskType.name(), "reason", "RESUME");
                    logIfNeeded(tracker);
                    continue;
                }

                // Check directory signature (incremental)
                if (directorySkipEnabled && isDirectoryUnchanged(config.getId(), dirInfo, dirPathMd5)) {
                    if (useSeenBasedDelete) {
                        recordSeenFilesForDirectory(taskId, dirInfo, supportedExtensions);
                    }
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    incrementCounter("music.scan.dir.skipped", 1,
                            "task_type", taskType.name(), "reason", "SIGNATURE");
                    logIfNeeded(tracker);
                    continue;
                }

                // Detect cover art
                String coverUrl = coverArtDetector.detectCoverInDirectory(dirInfo.getFiles());

                final WebDavDirectoryInfo finalDirInfo = dirInfo;
                final String finalDirPathMd5 = dirPathMd5;
                final String finalCoverUrl = coverUrl;
                completionService.submit(() -> processDirectoryTask(
                        taskId, config, finalDirInfo, finalDirPathMd5, finalCoverUrl,
                        supportedExtensions, lyricExtensions, useSeenBasedDelete, taskType));
                inFlight++;

                if (inFlight >= directoryProcessMaxInFlight) {
                    inFlight -= drainCompletedDirectoryTasks(
                            completionService, 1, taskId, config.getId(), result, tracker, taskType);
                } else {
                    inFlight -= drainCompletedDirectoryTasks(
                            completionService, 0, taskId, config.getId(), result, tracker, taskType);
                }

                // Persist progress
                if (tracker.shouldPersistProgress()) {
                    persistProgress(taskId, result, tracker);
                }

                logIfNeeded(tracker);
            }

            if (inFlight > 0) {
                inFlight -= drainCompletedDirectoryTasks(
                        completionService, inFlight, taskId, config.getId(), result, tracker, taskType);
            }

            // Post-scan: soft-delete + dedup
            if (!result.isCanceled()) {
                if (deleteDetectionEnabled) {
                    int deleted = useSeenBasedDelete
                            ? trackMapper.softDeleteByTaskId(taskId, config.getId())
                            : trackMapper.softDeleteByLastScanTaskId(taskId, config.getId());
                    result.setDeletedCount(deleted);
                    incrementCounter("music.scan.file.deleted", deleted, "task_type", taskType.name());
                }
                if (dedupEnabled) {
                    int deduped = duplicateFilterService.deduplicateTracks(config.getId());
                    result.setDeduplicatedCount(deduped);
                    incrementCounter("music.scan.file.deduplicated", deduped, "task_type", taskType.name());
                }
            }

            // Final progress persist
            persistProgress(taskId, result, tracker);
            if (result.isCanceled()) {
                metricStatus = "CANCELED";
            }
        } catch (RuntimeException e) {
            metricStatus = "FAILED";
            incrementCounter("music.scan.task.failed", 1, "task_type", taskType.name());
            throw e;
        } finally {
            directoryExecutor.shutdownNow();
            webDavClient.closeSession(session);
            cleanupSeenFiles(taskId);
            recordDuration("music.scan.task.duration", System.nanoTime() - taskStartNanos,
                    "task_type", taskType.name(), "status", metricStatus);
            incrementCounter("music.scan.task.finished", 1,
                    "task_type", taskType.name(), "status", metricStatus);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - taskStartNanos);
        log.info("PIPELINE_SCAN_FINISH taskId={} totalFiles={} audioFiles={} added={} updated={} deleted={} "
                + "deduped={} failed={} dirsTotal={} dirsSkipped={} elapsedMs={} elapsed={}",
                taskId, result.getTotalFiles(), result.getAudioFiles(), result.getAddedCount(),
                result.getUpdatedCount(), result.getDeletedCount(), result.getDeduplicatedCount(),
                result.getFailedCount(), tracker.getTotalDirectoriesDiscovered(),
                tracker.getSkippedDirectories(), elapsedMs, formatElapsed(elapsedMs));
        return result;
    }

    private DirectoryTaskOutcome processDirectoryTask(Long taskId, WebDavConfigEntity config,
                                                      WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                                      String coverUrl, Set<String> supportedExtensions,
                                                      Set<String> lyricExtensions, boolean collectSeenForDelete,
                                                      TaskType taskType) {
        long processStartNanos = System.nanoTime();
        try {
            DirProcessResult dirResult = processDirectoryFiles(
                    taskId, config, dirInfo, coverUrl, supportedExtensions, lyricExtensions, collectSeenForDelete);
            recordDuration("music.scan.dir.process.duration", System.nanoTime() - processStartNanos,
                    "task_type", taskType.name(), "result", "OK");
            return DirectoryTaskOutcome.success(dirInfo, dirPathMd5, dirResult);
        } catch (Exception e) {
            recordDuration("music.scan.dir.process.duration", System.nanoTime() - processStartNanos,
                    "task_type", taskType.name(), "result", "ERROR");
            return DirectoryTaskOutcome.failed(dirInfo, dirPathMd5, e);
        }
    }

    private int drainCompletedDirectoryTasks(CompletionService<DirectoryTaskOutcome> completionService,
                                             int requiredCount, Long taskId, Long configId,
                                             ScanResult result, ScanProgressTracker tracker,
                                             TaskType taskType) {
        int drained = 0;
        while (requiredCount <= 0 || drained < requiredCount) {
            Future<DirectoryTaskOutcome> future = null;
            try {
                if (requiredCount > 0 && drained < requiredCount) {
                    future = completionService.take();
                } else {
                    future = completionService.poll();
                    if (future == null) {
                        break;
                    }
                }
                DirectoryTaskOutcome outcome = future.get();
                applyDirectoryTaskOutcome(outcome, taskId, configId, result, tracker, taskType);
                drained++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("目录处理任务被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("目录处理任务执行失败", e.getCause());
            }
        }
        return drained;
    }

    private void applyDirectoryTaskOutcome(DirectoryTaskOutcome outcome,
                                           Long taskId, Long configId,
                                           ScanResult result, ScanProgressTracker tracker,
                                           TaskType taskType) {
        if (outcome.error != null) {
            String errorMessage = limitLength(outcome.error.getMessage(), 1000);
            saveCheckpoint(taskId, outcome.dirRelativePath, outcome.dirPathMd5,
                    "FAILED", outcome.fileCount, 0, 1, errorMessage);
            result.incrementFailedCount();
            tracker.onDirectoryCompleted(outcome.dirRelativePath, 0, 0, 0, 0, 1);
            incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "PROCESS");
            return;
        }

        DirProcessResult dirResult = outcome.dirResult;
        result.addDirResult(dirResult);
        int checkpointFailedCount = dirResult.failed;
        String checkpointStatus = dirResult.failed > 0 ? "FAILED" : "COMPLETED";
        String checkpointError = null;

        try {
            updateDirectorySignature(configId, outcome.dirInfo, outcome.dirPathMd5);
        } catch (Exception e) {
            checkpointStatus = "FAILED";
            checkpointFailedCount += 1;
            checkpointError = limitLength("目录签名更新失败: " + e.getMessage(), 1000);
            result.incrementFailedCount();
            incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "SIGNATURE");
            log.warn("Update directory signature failed, taskId={}, dir={}",
                    taskId, outcome.dirRelativePath, e);
        }

        try {
            saveCheckpoint(taskId, outcome.dirRelativePath, outcome.dirPathMd5,
                    checkpointStatus, outcome.fileCount, dirResult.processed, checkpointFailedCount, checkpointError);
        } catch (Exception e) {
            incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "CHECKPOINT");
            log.warn("Save checkpoint failed, taskId={}, dir={}", taskId, outcome.dirRelativePath, e);
        }

        tracker.onDirectoryCompleted(outcome.dirRelativePath,
                dirResult.processed, dirResult.added, dirResult.updated,
                dirResult.skipped, checkpointFailedCount);
        incrementCounter("music.scan.dir.processed", 1, "task_type", taskType.name());
        incrementCounter("music.scan.file.audio", dirResult.audioFiles, "task_type", taskType.name());
        incrementCounter("music.scan.file.added", dirResult.added, "task_type", taskType.name());
        incrementCounter("music.scan.file.updated", dirResult.updated, "task_type", taskType.name());
        incrementCounter("music.scan.file.skipped", dirResult.skipped, "task_type", taskType.name());
        incrementCounter("music.scan.file.failed", checkpointFailedCount, "task_type", taskType.name());
    }

    private DirProcessResult processDirectoryFiles(Long taskId, WebDavConfigEntity config,
                                                   WebDavDirectoryInfo dirInfo,
                                                   String coverUrl, Set<String> supportedExtensions,
                                                   Set<String> lyricExtensions,
                                                   boolean collectSeenForDelete) {
        DirProcessResult dirResult = new DirProcessResult();
        List<TrackEntity> trackBatch = new ArrayList<>();
        List<String> seenMd5Batch = new ArrayList<>();
        List<String> touchMd5Batch = new ArrayList<>();
        List<AudioCandidate> audioCandidates = new ArrayList<>();
        Map<String, String> lyricPathIndex = buildLyricPathIndex(dirInfo.getFiles(), lyricExtensions);
        int dbBatchSize = Math.max(10, appScanProperties.getDbBatchSize());

        for (WebDavFileObject file : dirInfo.getFiles()) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }
            if (!isAudioFile(relativePath, supportedExtensions)) {
                dirResult.skipped++;
                continue;
            }

            String pathMd5 = HashUtil.md5Hex(relativePath);
            audioCandidates.add(new AudioCandidate(file, relativePath, pathMd5));
            dirResult.audioFiles++;

            if (collectSeenForDelete) {
                seenMd5Batch.add(pathMd5);
            }
        }

        Map<String, TrackEntity> existingMap = loadExistingTrackMap(config.getId(), audioCandidates, dbBatchSize);
        for (AudioCandidate candidate : audioCandidates) {
            String relativePath = candidate.relativePath;
            String pathMd5 = candidate.pathMd5;
            WebDavFileObject file = candidate.file;
            try {
                TrackEntity existing = existingMap.get(pathMd5);
                // Pure WebDAV infer mode may evolve over time; recompute metadata from path/dir and
                // upsert when inferred fields differ, even if file fingerprint is unchanged.
                AudioMetadata metadata = new AudioMetadata();
                String lyricPath = resolveLyricPath(relativePath, lyricPathIndex);
                TrackEntity entity = buildTrackEntity(taskId, config.getId(), relativePath, pathMd5,
                        file, metadata, coverUrl, lyricPath);
                boolean activeExisting = existing != null && !Objects.equals(existing.getIsDeleted(), 1);
                if (activeExisting && sameFingerprint(existing, file) && sameTrackMetadata(existing, entity)) {
                    if (!collectSeenForDelete) {
                        touchMd5Batch.add(pathMd5);
                        if (touchMd5Batch.size() >= dbBatchSize) {
                            flushTouchedBatch(taskId, config.getId(), touchMd5Batch);
                        }
                    }
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
        if (!touchMd5Batch.isEmpty()) {
            flushTouchedBatch(taskId, config.getId(), touchMd5Batch);
        }

        // Batch insert seen files
        if (collectSeenForDelete && !seenMd5Batch.isEmpty()) {
            batchInsertSeenFiles(taskId, seenMd5Batch, dbBatchSize);
        }

        return dirResult;
    }

    private Map<String, TrackEntity> loadExistingTrackMap(Long configId, List<AudioCandidate> audioCandidates,
                                                          int dbBatchSize) {
        Map<String, TrackEntity> result = new HashMap<>();
        if (audioCandidates.isEmpty()) {
            return result;
        }

        Set<String> uniqueMd5 = new HashSet<>();
        for (AudioCandidate candidate : audioCandidates) {
            uniqueMd5.add(candidate.pathMd5);
        }
        List<String> md5List = new ArrayList<>(uniqueMd5);
        int queryBatchSize = Math.max(200, dbBatchSize * 4);
        for (int i = 0; i < md5List.size(); i += queryBatchSize) {
            int end = Math.min(i + queryBatchSize, md5List.size());
            List<String> subList = md5List.subList(i, end);
            List<TrackEntity> rows = trackMapper.selectByConfigAndPathMd5In(configId, subList);
            for (TrackEntity row : rows) {
                result.put(row.getSourcePathMd5(), row);
            }
        }
        return result;
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

    private void flushTouchedBatch(Long taskId, Long configId, List<String> touchedPathMd5Batch) {
        if (touchedPathMd5Batch.isEmpty()) {
            return;
        }
        try {
            trackMapper.touchLastScanTaskByPathMd5In(taskId, configId, touchedPathMd5Batch);
        } catch (Exception e) {
            log.warn("Batch touch last_scan_task_id failed, taskId={}, configId={}", taskId, configId, e);
        }
        touchedPathMd5Batch.clear();
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

    private void cleanupSeenFiles(Long taskId) {
        try {
            int deleted = scanTaskSeenFileMapper.deleteByTaskId(taskId);
            if (deleted > 0) {
                log.debug("Cleaned seen-file rows, taskId={}, rows={}", taskId, deleted);
            }
        } catch (Exception e) {
            log.warn("Cleanup seen-file rows failed, taskId={}", taskId, e);
        }
    }

    private void recordSeenFilesForDirectory(Long taskId, WebDavDirectoryInfo dirInfo,
                                               Set<String> supportedExtensions) {
        List<String> md5List = new ArrayList<>();
        for (WebDavFileObject file : dirInfo.getFiles()) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (!StringUtils.hasText(relativePath)) {
                continue;
            }
            if (!isAudioFile(relativePath, supportedExtensions)) {
                continue;
            }
            md5List.add(HashUtil.md5Hex(relativePath));
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
            LocalDateTime infoLm = toSecondPrecisionLocalDateTime(dirInfo.getLastModified());
            LocalDateTime existingLm = existing.getDirLastModified().truncatedTo(ChronoUnit.SECONDS);
            if (!existingLm.equals(infoLm)) {
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
            entity.setDirLastModified(toSecondPrecisionLocalDateTime(dirInfo.getLastModified()));
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
            entity.setSourceLastModified(toSecondPrecisionLocalDateTime(file.getLastModified()));
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
        if (entity.getHasCover() == 0 && StringUtils.hasText(coverUrl)) {
            entity.setHasCover(1);
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
            LocalDateTime lastModified = toSecondPrecisionLocalDateTime(file.getLastModified());
            LocalDateTime existingLastModified = existing.getSourceLastModified().truncatedTo(ChronoUnit.SECONDS);
            return existingLastModified.equals(lastModified)
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

    private LocalDateTime toSecondPrecisionLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS);
    }

    private String limitLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void incrementCounter(String name, double value, String... tags) {
        if (meterRegistry == null || value <= 0) {
            return;
        }
        try {
            meterRegistry.counter(name, tags).increment(value);
        } catch (Exception e) {
            log.debug("Metric counter update failed, name={}", name, e);
        }
    }

    private void recordDuration(String name, long nanos, String... tags) {
        if (meterRegistry == null || nanos <= 0) {
            return;
        }
        try {
            meterRegistry.timer(name, tags).record(nanos, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            log.debug("Metric timer update failed, name={}", name, e);
        }
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

    // --- Inner classes ---

    private static class DirectoryTaskOutcome {
        private final WebDavDirectoryInfo dirInfo;
        private final String dirRelativePath;
        private final String dirPathMd5;
        private final int fileCount;
        private final DirProcessResult dirResult;
        private final Exception error;

        private DirectoryTaskOutcome(WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                     DirProcessResult dirResult, Exception error) {
            this.dirInfo = dirInfo;
            this.dirRelativePath = dirInfo == null ? "" : dirInfo.getRelativePath();
            this.dirPathMd5 = dirPathMd5;
            this.fileCount = dirInfo == null || dirInfo.getFiles() == null ? 0 : dirInfo.getFiles().size();
            this.dirResult = dirResult;
            this.error = error;
        }

        private static DirectoryTaskOutcome success(WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                                    DirProcessResult dirResult) {
            return new DirectoryTaskOutcome(dirInfo, dirPathMd5, dirResult, null);
        }

        private static DirectoryTaskOutcome failed(WebDavDirectoryInfo dirInfo, String dirPathMd5, Exception error) {
            return new DirectoryTaskOutcome(dirInfo, dirPathMd5, null, error);
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);
        private final String prefix;

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + index.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    private static class AudioCandidate {
        private final WebDavFileObject file;
        private final String relativePath;
        private final String pathMd5;

        private AudioCandidate(WebDavFileObject file, String relativePath, String pathMd5) {
            this.file = file;
            this.relativePath = relativePath;
            this.pathMd5 = pathMd5;
        }
    }

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
