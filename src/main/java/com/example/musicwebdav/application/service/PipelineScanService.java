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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
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

        ScanProgressTracker tracker = new ScanProgressTracker(30,
                appScanProperties.getProgressPersistIntervalSec(),
                appScanProperties.getLargeDirWarnThreshold());
        String rootUrl = webDavClient.buildRootUrl(config.getBaseUrl(), config.getRootPath());

        log.info("PIPELINE_SCAN_START taskId={} configId={} configName={} rootUrl={}",
                taskId, config.getId(), config.getName(), rootUrl);
        log.info("PIPELINE_SCAN_METADATA_MODE taskId={} mode=WEBDAV_INFER_ONLY", taskId);
        boolean isIncremental = TaskType.INCREMENTAL == taskType;
        final boolean directorySkipEnabled = isIncremental
                ? appScanProperties.isIncrementalDirectorySkipEnabled()
                : appScanProperties.isFullDirectorySkipEnabled();
        final boolean deleteDetectionEnabled = isIncremental
                ? appScanProperties.isIncrementalEnableDeleteDetection()
                : appScanProperties.isFullEnableDeleteDetection();
        final boolean dedupEnabled = isIncremental
                ? appScanProperties.isIncrementalEnableDedup()
                : appScanProperties.isFullEnableDedup();
        final boolean hasResumeCheckpoints = resumedCheckpoints != null && !resumedCheckpoints.isEmpty();
        // Resume checkpoints keep strict seen-file fallback. Full scan can switch to prefix-touch mode to reduce writes.
        final boolean useSeenBasedDelete = deleteDetectionEnabled
                && (hasResumeCheckpoints || (isIncremental
                ? directorySkipEnabled
                : appScanProperties.isFullSeenDeleteFallbackEnabled()));
        final int directoryListThreadCount = Math.max(1, appScanProperties.getDirectoryListThreadCount());
        final int directoryListMaxInFlight = Math.max(directoryListThreadCount,
                appScanProperties.getDirectoryListMaxInFlight());
        final int directoryProcessThreadCount = Math.max(1, appScanProperties.getDirectoryProcessThreadCount());
        final int directoryProcessMaxInFlight = Math.max(directoryProcessThreadCount,
                appScanProperties.getDirectoryProcessMaxInFlight());
        final ScanTelemetry telemetry = new ScanTelemetry(taskId, config.getId(), taskType.name());

        // When not using seen-based delete, defer per-directory touch operations to post-scan.
        // Instead of running LIKE-based UPDATE per skipped directory (O(dirs * tracks)),
        // we do a single config-wide touch at the end (O(tracks)).
        final boolean deferTouchToPostScan = deleteDetectionEnabled && !useSeenBasedDelete;

        log.info("PIPELINE_SCAN_SWITCHES taskId={} taskType={} directorySkip={} deleteDetection={} dedup={} "
                        + "seenDelete={} deferTouch={}",
                taskId, taskType.name(), directorySkipEnabled, deleteDetectionEnabled, dedupEnabled,
                useSeenBasedDelete, deferTouchToPostScan);
        final int smallDirMergeThreshold = Math.max(0, appScanProperties.getSmallDirMergeThreshold());
        log.info("PIPELINE_SCAN_PARALLEL taskId={} listWorkers={} listMaxInFlight={} processWorkers={} processMaxInFlight={} "
                        + "dbBatchSize={} smallDirMerge={} largeDirWarn={}",
                taskId, directoryListThreadCount, directoryListMaxInFlight,
                directoryProcessThreadCount, directoryProcessMaxInFlight,
                appScanProperties.getDbBatchSize(), smallDirMergeThreshold,
                appScanProperties.getLargeDirWarnThreshold());
        incrementCounter("music.scan.task.started", 1, "task_type", taskType.name());

        String metricStatus = "SUCCESS";
        long taskStartNanos = System.nanoTime();
        final ThreadLocal<Sardine> listSessionHolder = new ThreadLocal<>();
        final ConcurrentLinkedQueue<Sardine> listSessions = new ConcurrentLinkedQueue<>();
        ExecutorService listExecutor = Executors.newFixedThreadPool(
                directoryListThreadCount, new NamedThreadFactory("scan-list-"));
        ExecutorService directoryExecutor = Executors.newFixedThreadPool(
                directoryProcessThreadCount, new NamedThreadFactory("scan-dir-"));
        CompletionService<DirectoryListOutcome> listCompletionService =
                new ExecutorCompletionService<>(listExecutor);
        CompletionService<DirectoryTaskOutcome> processCompletionService =
                new ExecutorCompletionService<>(directoryExecutor);
        int listInFlight = 0;
        int processInFlight = 0;
        try {
            Deque<String> dirQueue = new ArrayDeque<>();
            Set<String> scheduled = new HashSet<>();
            dirQueue.push(rootUrl);
            scheduled.add(normalizeUrl(rootUrl));
            tracker.addDiscoveredDirectories(1);

            // Small-directory merge batch: accumulates small dirs to submit as a single process task
            List<SmallDirEntry> smallDirBatch = new ArrayList<>();
            int smallDirBatchFileCount = 0;
            int dbBatchSize = Math.max(10, appScanProperties.getDbBatchSize());

            while (!dirQueue.isEmpty() || listInFlight > 0) {
                if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                    result.setCanceled(true);
                    log.info("PIPELINE_SCAN_CANCELED taskId={}", taskId);
                    break;
                }

                while (!dirQueue.isEmpty() && listInFlight < directoryListMaxInFlight) {
                    final String dirUrl = dirQueue.pop();
                    listCompletionService.submit(() -> listDirectoryTask(
                            taskId, config, plainPassword, rootUrl, dirUrl, taskType,
                            listSessionHolder, listSessions, telemetry));
                    listInFlight++;
                }

                if (listInFlight <= 0) {
                    continue;
                }

                DirectoryListOutcome listOutcome = takeDirectoryListOutcome(listCompletionService);
                listInFlight--;

                if (listOutcome.error != null) {
                    String failedRelPath = listOutcome.dirUrl.startsWith(rootUrl)
                            ? listOutcome.dirUrl.substring(rootUrl.length())
                            : listOutcome.dirUrl;
                    String failedPathMd5 = HashUtil.md5Hex(safeRelativePath(failedRelPath));
                    saveCheckpoint(taskId, failedRelPath, failedPathMd5, "FAILED", 0, 0, 1,
                            limitLength(listOutcome.error.getMessage(), 1000));
                    result.incrementFailedCount();
                    incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "LIST");
                    continue;
                }

                WebDavDirectoryInfo dirInfo = listOutcome.dirInfo;

                // Enqueue subdirectories
                int discovered = 0;
                for (String subdir : dirInfo.getSubdirectoryUrls()) {
                    String subdirKey = normalizeUrl(subdir);
                    if (scheduled.add(subdirKey)) {
                        dirQueue.push(subdir);
                        discovered++;
                    }
                }
                if (discovered > 0) {
                    tracker.addDiscoveredDirectories(discovered);
                }
                tracker.onDirectoryDiscovered(dirInfo.getRelativePath(), dirInfo.getFiles().size());

                String dirPathMd5 = HashUtil.md5Hex(safeRelativePath(dirInfo.getRelativePath()));

                // Check resume checkpoint
                if (resumedCheckpoints != null && resumedCheckpoints.contains(dirPathMd5)) {
                    markSkippedDirectory(taskId, config.getId(), dirInfo, supportedExtensions,
                            deleteDetectionEnabled, useSeenBasedDelete, deferTouchToPostScan, telemetry, taskType);
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    incrementCounter("music.scan.dir.skipped", 1,
                            "task_type", taskType.name(), "reason", "RESUME");
                    logIfNeeded(tracker);
                    continue;
                }

                // Check directory signature
                if (directorySkipEnabled && isDirectoryUnchanged(config.getId(), dirInfo, dirPathMd5)) {
                    markSkippedDirectory(taskId, config.getId(), dirInfo, supportedExtensions,
                            deleteDetectionEnabled, useSeenBasedDelete, deferTouchToPostScan, telemetry, taskType);
                    tracker.onDirectorySkipped(dirInfo.getRelativePath());
                    incrementCounter("music.scan.dir.skipped", 1,
                            "task_type", taskType.name(), "reason", "SIGNATURE");
                    logIfNeeded(tracker);
                    continue;
                }

                // Detect cover art
                String coverUrl = coverArtDetector.detectCoverInDirectory(dirInfo.getFiles());

                // Count audio files for small-dir merging decision
                int audioFileCount = countAudioFiles(dirInfo.getFiles(), supportedExtensions);

                // Small-directory merging: accumulate tiny dirs and submit as a single process task
                if (smallDirMergeThreshold > 0 && audioFileCount <= smallDirMergeThreshold) {
                    smallDirBatch.add(new SmallDirEntry(dirInfo, dirPathMd5, coverUrl));
                    smallDirBatchFileCount += audioFileCount;

                    // Flush merged batch when accumulated enough files
                    if (smallDirBatchFileCount >= dbBatchSize) {
                        final List<SmallDirEntry> batchToSubmit = new ArrayList<>(smallDirBatch);
                        processCompletionService.submit(() -> processMergedDirectoryTask(
                                taskId, config, batchToSubmit,
                                supportedExtensions, lyricExtensions, useSeenBasedDelete, deferTouchToPostScan,
                                taskType, telemetry));
                        processInFlight++;
                        smallDirBatch.clear();
                        smallDirBatchFileCount = 0;
                    }
                } else {
                    // Normal submission for larger directories
                    final WebDavDirectoryInfo finalDirInfo = dirInfo;
                    final String finalDirPathMd5 = dirPathMd5;
                    final String finalCoverUrl = coverUrl;
                    processCompletionService.submit(() -> processDirectoryTask(
                            taskId, config, finalDirInfo, finalDirPathMd5, finalCoverUrl,
                            supportedExtensions, lyricExtensions, useSeenBasedDelete, deferTouchToPostScan,
                            taskType, telemetry));
                    processInFlight++;
                }

                if (processInFlight >= directoryProcessMaxInFlight) {
                    processInFlight -= drainCompletedDirectoryTasks(
                            processCompletionService, 1, taskId, config.getId(), result, tracker, taskType, telemetry);
                } else {
                    processInFlight -= drainCompletedDirectoryTasks(
                            processCompletionService, 0, taskId, config.getId(), result, tracker, taskType, telemetry);
                }

                // Persist progress
                if (tracker.shouldPersistProgress()) {
                    persistProgress(taskId, result, tracker);
                }

                logIfNeeded(tracker);
            }

            if (!result.isCanceled() && listInFlight > 0) {
                while (listInFlight > 0) {
                    DirectoryListOutcome listOutcome = takeDirectoryListOutcome(listCompletionService);
                    listInFlight--;
                    if (listOutcome.error != null) {
                        String failedRelPath = listOutcome.dirUrl.startsWith(rootUrl)
                                ? listOutcome.dirUrl.substring(rootUrl.length())
                                : listOutcome.dirUrl;
                        String failedPathMd5 = HashUtil.md5Hex(safeRelativePath(failedRelPath));
                        saveCheckpoint(taskId, failedRelPath, failedPathMd5, "FAILED", 0, 0, 1,
                                limitLength(listOutcome.error.getMessage(), 1000));
                        result.incrementFailedCount();
                        incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "LIST");
                    }
                }
            }

            // Flush remaining small-directory merge batch
            if (!result.isCanceled() && !smallDirBatch.isEmpty()) {
                final List<SmallDirEntry> remainingBatch = new ArrayList<>(smallDirBatch);
                processCompletionService.submit(() -> processMergedDirectoryTask(
                        taskId, config, remainingBatch,
                        supportedExtensions, lyricExtensions, useSeenBasedDelete, deferTouchToPostScan,
                        taskType, telemetry));
                processInFlight++;
                smallDirBatch.clear();
                smallDirBatchFileCount = 0;
            }

            if (!result.isCanceled() && processInFlight > 0) {
                processInFlight -= drainCompletedDirectoryTasks(
                        processCompletionService, processInFlight, taskId, config.getId(), result, tracker, taskType, telemetry);
            }

            // Phase transition: all dirs processed, entering post-scan phase
            tracker.enterProcessPhase();

            // Post-scan: deferred touch + soft-delete + dedup
            if (!result.isCanceled()) {
                if (deferTouchToPostScan) {
                    // Single config-wide UPDATE replaces thousands of per-directory LIKE UPDATEs.
                    // This is the critical optimization for repeat scans: O(1) instead of O(dirs).
                    long touchStartNanos = System.nanoTime();
                    try {
                        int touchedRows = trackMapper.touchLastScanTaskByConfig(taskId, config.getId());
                        long touchElapsed = System.nanoTime() - touchStartNanos;
                        telemetry.recordTouchByPrefix(touchElapsed);
                        log.info("POST_SCAN_BULK_TOUCH taskId={} configId={} touchedRows={} elapsedMs={}",
                                taskId, config.getId(), touchedRows,
                                String.format("%.1f", touchElapsed / 1_000_000.0));
                    } catch (Exception e) {
                        telemetry.recordTouchByPrefixFailed();
                        log.warn("Post-scan bulk touch failed, taskId={}, configId={}", taskId, config.getId(), e);
                    }
                }
                if (deleteDetectionEnabled) {
                    int deleted = useSeenBasedDelete
                            ? trackMapper.softDeleteByTaskId(taskId, config.getId())
                            : trackMapper.softDeleteByLastScanTaskId(taskId, config.getId());
                    result.setDeletedCount(deleted);
                    incrementCounter("music.scan.file.deleted", deleted, "task_type", taskType.name());
                }
                if (shouldRunDedup(taskType, dedupEnabled, result)) {
                    long dedupStartNanos = System.nanoTime();
                    int deduped = duplicateFilterService.deduplicateTracks(config.getId());
                    long dedupElapsed = System.nanoTime() - dedupStartNanos;
                    telemetry.recordDedup(dedupElapsed, deduped);
                    recordDuration("music.scan.dedup.duration", dedupElapsed, "task_type", taskType.name());
                    incrementCounter("music.scan.dedup.affected", deduped, "task_type", taskType.name());
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
            listExecutor.shutdownNow();
            directoryExecutor.shutdownNow();
            for (Sardine listSession : listSessions) {
                webDavClient.closeSession(listSession);
            }
            cleanupSeenFiles(taskId);
            recordDuration("music.scan.task.duration", System.nanoTime() - taskStartNanos,
                    "task_type", taskType.name(), "status", metricStatus);
            incrementCounter("music.scan.task.finished", 1,
                    "task_type", taskType.name(), "status", metricStatus);
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - taskStartNanos);
        telemetry.logSummary(elapsedMs, result);
        logDetailedSummary(taskId, config, taskType, result, tracker, elapsedMs);
        return result;
    }

    private DirectoryListOutcome listDirectoryTask(Long taskId,
                                                   WebDavConfigEntity config,
                                                   String plainPassword,
                                                   String rootUrl,
                                                   String dirUrl,
                                                   TaskType taskType,
                                                   ThreadLocal<Sardine> listSessionHolder,
                                                   ConcurrentLinkedQueue<Sardine> listSessions,
                                                   ScanTelemetry telemetry) {
        Sardine session = listSessionHolder.get();
        if (session == null) {
            session = webDavClient.createSession(config.getUsername(), plainPassword);
            listSessionHolder.set(session);
            listSessions.add(session);
        }

        long listStartNanos = System.nanoTime();
        try {
            WebDavDirectoryInfo dirInfo = webDavClient.listDirectory(session, dirUrl, rootUrl);
            telemetry.recordListSuccess(System.nanoTime() - listStartNanos);
            recordDuration("music.scan.webdav.list_dir.duration", System.nanoTime() - listStartNanos,
                    "task_type", taskType.name(), "result", "OK");
            return DirectoryListOutcome.success(dirUrl, dirInfo);
        } catch (Exception e) {
            telemetry.recordListFailed(System.nanoTime() - listStartNanos);
            recordDuration("music.scan.webdav.list_dir.duration", System.nanoTime() - listStartNanos,
                    "task_type", taskType.name(), "result", "ERROR");
            log.warn("PIPELINE_SCAN_DIR_ERROR taskId={} dirUrl={} error={}", taskId, dirUrl, e.getMessage());
            return DirectoryListOutcome.failed(dirUrl, e);
        }
    }

    private DirectoryListOutcome takeDirectoryListOutcome(
            CompletionService<DirectoryListOutcome> listCompletionService) {
        try {
            Future<DirectoryListOutcome> future = listCompletionService.take();
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("目录枚举任务被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("目录枚举任务执行失败", e.getCause());
        }
    }

    private void markSkippedDirectory(Long taskId, Long configId, WebDavDirectoryInfo dirInfo,
                                      Set<String> supportedExtensions,
                                      boolean deleteDetectionEnabled,
                                      boolean useSeenBasedDelete,
                                      boolean deferTouchToPostScan,
                                      ScanTelemetry telemetry,
                                      TaskType taskType) {
        if (!deleteDetectionEnabled) {
            return;
        }
        if (useSeenBasedDelete) {
            recordSeenFilesForDirectory(taskId, dirInfo, supportedExtensions, telemetry, taskType);
            return;
        }
        // When deferTouchToPostScan is true, skip per-directory LIKE UPDATE entirely.
        // A single config-wide touch will be done in post-scan phase instead.
        if (deferTouchToPostScan) {
            return;
        }
        touchLastScanTaskForDirectory(taskId, configId,
                dirInfo == null ? null : dirInfo.getRelativePath(), telemetry, taskType);
    }

    private void touchLastScanTaskForDirectory(Long taskId, Long configId, String dirRelativePath,
                                               ScanTelemetry telemetry, TaskType taskType) {
        String normalized = normalizeRelativePath(dirRelativePath);
        long startNanos = System.nanoTime();
        try {
            if (!StringUtils.hasText(normalized)) {
                trackMapper.touchLastScanTaskByConfig(taskId, configId);
                long elapsed = System.nanoTime() - startNanos;
                telemetry.recordTouchByPrefix(elapsed);
                recordDuration("music.scan.db.touch_by_prefix.duration", elapsed,
                        "task_type", taskType.name(), "result", "OK");
                return;
            }
            String escaped = escapeLikePattern(normalized);
            String likePattern = (escaped.endsWith("/") ? escaped : escaped + "/") + "%";
            trackMapper.touchLastScanTaskByDirectoryPrefix(taskId, configId, likePattern);
            long elapsed = System.nanoTime() - startNanos;
            telemetry.recordTouchByPrefix(elapsed);
            recordDuration("music.scan.db.touch_by_prefix.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
        } catch (Exception e) {
            telemetry.recordTouchByPrefixFailed();
            recordDuration("music.scan.db.touch_by_prefix.duration", System.nanoTime() - startNanos,
                    "task_type", taskType.name(), "result", "ERROR");
            log.warn("Touch last_scan_task_id by directory failed, taskId={}, configId={}, dir={}",
                    taskId, configId, dirRelativePath, e);
        }
    }

    private String escapeLikePattern(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private DirectoryTaskOutcome processDirectoryTask(Long taskId, WebDavConfigEntity config,
                                                      WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                                      String coverUrl, Set<String> supportedExtensions,
                                                      Set<String> lyricExtensions, boolean collectSeenForDelete,
                                                      boolean deferTouchToPostScan,
                                                      TaskType taskType,
                                                      ScanTelemetry telemetry) {
        long processStartNanos = System.nanoTime();
        try {
            DirProcessResult dirResult = processDirectoryFiles(
                    taskId, config, dirInfo, coverUrl, supportedExtensions, lyricExtensions, collectSeenForDelete,
                    deferTouchToPostScan, telemetry, taskType);
            long elapsed = System.nanoTime() - processStartNanos;
            telemetry.recordProcessSuccess(elapsed);
            recordDuration("music.scan.dir.process.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
            return DirectoryTaskOutcome.success(dirInfo, dirPathMd5, dirResult);
        } catch (Exception e) {
            long elapsed = System.nanoTime() - processStartNanos;
            telemetry.recordProcessFailed(elapsed);
            recordDuration("music.scan.dir.process.duration", elapsed,
                    "task_type", taskType.name(), "result", "ERROR");
            return DirectoryTaskOutcome.failed(dirInfo, dirPathMd5, e);
        }
    }

    private int drainCompletedDirectoryTasks(CompletionService<DirectoryTaskOutcome> completionService,
                                             int requiredCount, Long taskId, Long configId,
                                             ScanResult result, ScanProgressTracker tracker,
                                             TaskType taskType,
                                             ScanTelemetry telemetry) {
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
                applyDirectoryTaskOutcome(outcome, taskId, configId, result, tracker, taskType, telemetry);
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
                                           TaskType taskType,
                                           ScanTelemetry telemetry) {
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

        // For merged small-directory batches, save checkpoint/signature per entry
        if (outcome.isMerged()) {
            for (SmallDirEntry entry : outcome.mergedEntries) {
                applySingleDirCheckpoint(taskId, configId, entry.dirInfo, entry.dirPathMd5,
                        taskType, telemetry);
            }
        } else {
            applySingleDirCheckpoint(taskId, configId, outcome.dirInfo, outcome.dirPathMd5,
                    taskType, telemetry);
        }

        int checkpointFailedCount = dirResult.failed;

        // For merged outcomes, report completion for each constituent directory
        if (outcome.isMerged()) {
            int entryCount = outcome.mergedEntries.size();
            // Distribute counts across entries for tracker (approximate)
            for (int i = 0; i < entryCount; i++) {
                SmallDirEntry entry = outcome.mergedEntries.get(i);
                if (i == 0) {
                    // First entry gets the counts
                    tracker.onDirectoryCompleted(entry.dirInfo.getRelativePath(),
                            dirResult.processed, dirResult.added, dirResult.updated,
                            dirResult.skipped, checkpointFailedCount);
                } else {
                    // Remaining entries are counted as completed with zero counts
                    tracker.onDirectoryCompleted(entry.dirInfo.getRelativePath(), 0, 0, 0, 0, 0);
                }
            }
            incrementCounter("music.scan.dir.processed", entryCount, "task_type", taskType.name());
        } else {
            tracker.onDirectoryCompleted(outcome.dirRelativePath,
                    dirResult.processed, dirResult.added, dirResult.updated,
                    dirResult.skipped, checkpointFailedCount);
            incrementCounter("music.scan.dir.processed", 1, "task_type", taskType.name());
        }

        incrementCounter("music.scan.file.audio", dirResult.audioFiles, "task_type", taskType.name());
        incrementCounter("music.scan.file.added", dirResult.added, "task_type", taskType.name());
        incrementCounter("music.scan.file.updated", dirResult.updated, "task_type", taskType.name());
        incrementCounter("music.scan.file.skipped", dirResult.skipped, "task_type", taskType.name());
        incrementCounter("music.scan.file.failed", checkpointFailedCount, "task_type", taskType.name());
    }

    private void applySingleDirCheckpoint(Long taskId, Long configId,
                                           WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                           TaskType taskType, ScanTelemetry telemetry) {
        String checkpointStatus = "COMPLETED";
        String checkpointError = null;
        int failedCount = 0;

        try {
            long signatureStart = System.nanoTime();
            updateDirectorySignature(configId, dirInfo, dirPathMd5);
            long elapsed = System.nanoTime() - signatureStart;
            telemetry.recordSignatureUpdate(elapsed);
            recordDuration("music.scan.db.signature_upsert.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
        } catch (Exception e) {
            checkpointStatus = "FAILED";
            failedCount = 1;
            checkpointError = limitLength("目录签名更新失败: " + e.getMessage(), 1000);
            telemetry.recordSignatureUpdateFailed();
            incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "SIGNATURE");
            log.warn("Update directory signature failed, taskId={}, dir={}",
                    taskId, dirInfo.getRelativePath(), e);
        }

        try {
            int fileCount = dirInfo.getFiles() == null ? 0 : dirInfo.getFiles().size();
            long checkpointStart = System.nanoTime();
            saveCheckpoint(taskId, dirInfo.getRelativePath(), dirPathMd5,
                    checkpointStatus, fileCount, fileCount, failedCount, checkpointError);
            long elapsed = System.nanoTime() - checkpointStart;
            telemetry.recordCheckpointUpdate(elapsed);
            recordDuration("music.scan.db.checkpoint_upsert.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
        } catch (Exception e) {
            telemetry.recordCheckpointUpdateFailed();
            incrementCounter("music.scan.dir.failed", 1, "task_type", taskType.name(), "stage", "CHECKPOINT");
            log.warn("Save checkpoint failed, taskId={}, dir={}", taskId, dirInfo.getRelativePath(), e);
        }
    }

    private DirProcessResult processDirectoryFiles(Long taskId, WebDavConfigEntity config,
                                                   WebDavDirectoryInfo dirInfo,
                                                   String coverUrl, Set<String> supportedExtensions,
                                                   Set<String> lyricExtensions,
                                                   boolean collectSeenForDelete,
                                                   boolean deferTouchToPostScan,
                                                   ScanTelemetry telemetry,
                                                   TaskType taskType) {
        DirProcessResult dirResult = new DirProcessResult();
        List<TrackEntity> trackBatch = new ArrayList<>();
        List<String> seenMd5Batch = new ArrayList<>();
        List<String> touchMd5Batch = new ArrayList<>();
        List<AudioCandidate> audioCandidates = new ArrayList<>();
        Map<String, String> lyricPathIndex = buildLyricPathIndex(dirInfo.getFiles(), lyricExtensions);
        int dbBatchSize = Math.max(10, appScanProperties.getDbBatchSize());
        int bulkWriteSize = appScanProperties.getBulkWriteBatchSize() > 0
                ? appScanProperties.getBulkWriteBatchSize()
                : Math.max(10, dbBatchSize * 2);

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
                    // Skip per-file touch when deferred to post-scan bulk touch
                    if (!collectSeenForDelete && !deferTouchToPostScan) {
                        touchMd5Batch.add(pathMd5);
                        if (touchMd5Batch.size() >= bulkWriteSize) {
                            flushTouchedBatch(taskId, config.getId(), touchMd5Batch, telemetry, taskType);
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
                    flushTrackBatch(trackBatch, telemetry, taskType);
                }
            } catch (Exception e) {
                dirResult.failed++;
                dirResult.processed++;
                log.warn("Scan file failed, taskId={}, path={}", taskId, relativePath, e);
            }
        }

        // Flush remaining tracks
        if (!trackBatch.isEmpty()) {
            flushTrackBatch(trackBatch, telemetry, taskType);
        }
        if (!touchMd5Batch.isEmpty()) {
            flushTouchedBatch(taskId, config.getId(), touchMd5Batch, telemetry, taskType);
        }

        // Batch insert seen files
        if (collectSeenForDelete && !seenMd5Batch.isEmpty()) {
            batchInsertSeenFiles(taskId, seenMd5Batch, bulkWriteSize, telemetry, taskType);
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

    private void flushTrackBatch(List<TrackEntity> batch, ScanTelemetry telemetry, TaskType taskType) {
        if (batch.isEmpty()) {
            return;
        }
        long startNanos = System.nanoTime();
        int batchSize = batch.size();
        try {
            trackMapper.batchUpsert(batch);
            long elapsed = System.nanoTime() - startNanos;
            telemetry.recordBatchUpsert(elapsed, batchSize);
            recordDuration("music.scan.db.batch_upsert.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
            incrementCounter("music.scan.db.batch_upsert.rows", batchSize, "task_type", taskType.name());
        } catch (Exception e) {
            log.warn("Batch upsert failed, falling back to individual inserts", e);
            telemetry.recordBatchUpsertFailed();
            recordDuration("music.scan.db.batch_upsert.duration", System.nanoTime() - startNanos,
                    "task_type", taskType.name(), "result", "ERROR");
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

    private void flushTouchedBatch(Long taskId, Long configId, List<String> touchedPathMd5Batch,
                                   ScanTelemetry telemetry, TaskType taskType) {
        if (touchedPathMd5Batch.isEmpty()) {
            return;
        }
        long startNanos = System.nanoTime();
        int touchedCount = touchedPathMd5Batch.size();
        try {
            trackMapper.touchLastScanTaskByPathMd5In(taskId, configId, touchedPathMd5Batch);
            long elapsed = System.nanoTime() - startNanos;
            telemetry.recordTouchUpdate(elapsed, touchedCount);
            recordDuration("music.scan.db.touch_by_md5.duration", elapsed,
                    "task_type", taskType.name(), "result", "OK");
            incrementCounter("music.scan.db.touch_by_md5.rows", touchedCount, "task_type", taskType.name());
        } catch (Exception e) {
            telemetry.recordTouchUpdateFailed();
            recordDuration("music.scan.db.touch_by_md5.duration", System.nanoTime() - startNanos,
                    "task_type", taskType.name(), "result", "ERROR");
            log.warn("Batch touch last_scan_task_id failed, taskId={}, configId={}", taskId, configId, e);
        }
        touchedPathMd5Batch.clear();
    }

    private void batchInsertSeenFiles(Long taskId, List<String> md5List, int batchSize,
                                      ScanTelemetry telemetry, TaskType taskType) {
        for (int i = 0; i < md5List.size(); i += batchSize) {
            int end = Math.min(i + batchSize, md5List.size());
            List<String> subList = md5List.subList(i, end);
            long startNanos = System.nanoTime();
            try {
                scanTaskSeenFileMapper.batchInsert(taskId, subList);
                long elapsed = System.nanoTime() - startNanos;
                telemetry.recordSeenInsert(elapsed, subList.size());
                recordDuration("music.scan.db.seen_insert.duration", elapsed,
                        "task_type", taskType.name(), "result", "OK");
                incrementCounter("music.scan.db.seen_insert.rows", subList.size(), "task_type", taskType.name());
            } catch (Exception e) {
                telemetry.recordSeenInsertFailed();
                recordDuration("music.scan.db.seen_insert.duration", System.nanoTime() - startNanos,
                        "task_type", taskType.name(), "result", "ERROR");
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
                                             Set<String> supportedExtensions,
                                             ScanTelemetry telemetry, TaskType taskType) {
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
            int bulkSize = appScanProperties.getBulkWriteBatchSize() > 0
                    ? appScanProperties.getBulkWriteBatchSize()
                    : Math.max(10, appScanProperties.getDbBatchSize() * 2);
            batchInsertSeenFiles(taskId, md5List, bulkSize, telemetry, taskType);
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
            // Exact match key (strip extension only)
            String exactKey = buildPathWithoutExtensionKey(relativePath);
            if (StringUtils.hasText(exactKey)) {
                lyricPathIndex.putIfAbsent(exactKey, relativePath);
            }
            // Fuzzy match key (strip extension + leading numbers/symbols)
            String fuzzyKey = buildFuzzyLyricKey(relativePath);
            if (StringUtils.hasText(fuzzyKey)) {
                lyricPathIndex.putIfAbsent(fuzzyKey, relativePath);
            }
        }
        return lyricPathIndex;
    }

    private String resolveLyricPath(String audioRelativePath, Map<String, String> lyricPathIndex) {
        if (!StringUtils.hasText(audioRelativePath) || lyricPathIndex == null || lyricPathIndex.isEmpty()) {
            return null;
        }
        // 1. Try exact match
        String exactKey = buildPathWithoutExtensionKey(audioRelativePath);
        if (StringUtils.hasText(exactKey)) {
            String path = lyricPathIndex.get(exactKey);
            if (path != null) return path;
        }
        // 2. Try fuzzy match
        String fuzzyKey = buildFuzzyLyricKey(audioRelativePath);
        if (StringUtils.hasText(fuzzyKey)) {
            return lyricPathIndex.get(fuzzyKey);
        }
        return null;
    }

    private String buildFuzzyLyricKey(String relativePath) {
        String name = stripFileExtension(relativePath);
        if (name == null) {
            name = relativePath;
        }
        // Remove path segments (get only filename)
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // Remove leading numbers, dots, spaces, dashes (e.g. "01. ", "02-")
        String fuzzy = name.replaceAll("^[0-9\\s\\.\\-_]+", "");
        if (fuzzy.isEmpty()) {
            return name.toLowerCase(Locale.ROOT);
        }
        return fuzzy.toLowerCase(Locale.ROOT);
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

    private boolean shouldRunDedup(TaskType taskType, boolean dedupEnabled, ScanResult result) {
        if (!dedupEnabled) {
            return false;
        }
        int changedCount = result.getAddedCount() + result.getUpdatedCount() + result.getDeletedCount();
        if (changedCount <= 0) {
            log.info("DEDUP_DECISION skip reason=NO_CHANGE taskType={}", taskType.name());
            return false;
        }

        if (taskType != TaskType.FULL) {
            log.info("DEDUP_DECISION run reason=NON_FULL_TASK taskType={} changedCount={}",
                    taskType.name(), changedCount);
            return true;
        }

        int totalAudio = Math.max(result.getAudioFiles(), result.getTotalFiles());
        int smallLibraryMax = Math.max(0, appScanProperties.getDedupAlwaysForSmallLibraryMaxFiles());
        if (smallLibraryMax > 0 && totalAudio > 0 && totalAudio <= smallLibraryMax) {
            log.info("DEDUP_DECISION run reason=SMALL_LIBRARY changedCount={} totalAudio={} smallMax={}",
                    changedCount, totalAudio, smallLibraryMax);
            return true;
        }

        int minChangedCount = Math.max(0, appScanProperties.getDedupMinChangedCount());
        double minChangedRatio = Math.max(0D, appScanProperties.getDedupMinChangedRatio());
        double changedRatio = totalAudio <= 0 ? 0D : (changedCount * 1.0D / totalAudio);
        boolean byCount = changedCount >= minChangedCount;
        boolean byRatio = changedRatio >= minChangedRatio;
        if (byCount || byRatio) {
            log.info("DEDUP_DECISION run reason={} changedCount={} changedRatio={} minCount={} minRatio={}",
                    byCount ? "COUNT_THRESHOLD" : "RATIO_THRESHOLD",
                    changedCount, String.format("%.5f", changedRatio), minChangedCount,
                    String.format("%.5f", minChangedRatio));
            return true;
        }

        log.info("DEDUP_DECISION skip reason=BELOW_THRESHOLD changedCount={} changedRatio={} minCount={} minRatio={}",
                changedCount, String.format("%.5f", changedRatio), minChangedCount,
                String.format("%.5f", minChangedRatio));
        return false;
    }

    // ── Merged small-directory processing ─────────────────

    private DirectoryTaskOutcome processMergedDirectoryTask(Long taskId, WebDavConfigEntity config,
                                                             List<SmallDirEntry> entries,
                                                             Set<String> supportedExtensions,
                                                             Set<String> lyricExtensions,
                                                             boolean collectSeenForDelete,
                                                             boolean deferTouchToPostScan,
                                                             TaskType taskType,
                                                             ScanTelemetry telemetry) {
        // Process all small directories as a single batch to reduce overhead.
        // We return a composite DirectoryTaskOutcome for the first entry and
        // accumulate results across all entries.
        DirProcessResult compositeResult = new DirProcessResult();
        Exception firstError = null;
        WebDavDirectoryInfo firstDirInfo = entries.get(0).dirInfo;
        String firstDirPathMd5 = entries.get(0).dirPathMd5;

        long processStartNanos = System.nanoTime();
        for (SmallDirEntry entry : entries) {
            try {
                DirProcessResult dirResult = processDirectoryFiles(
                        taskId, config, entry.dirInfo, entry.coverUrl, supportedExtensions,
                        lyricExtensions, collectSeenForDelete, deferTouchToPostScan, telemetry, taskType);
                compositeResult.processed += dirResult.processed;
                compositeResult.added += dirResult.added;
                compositeResult.updated += dirResult.updated;
                compositeResult.skipped += dirResult.skipped;
                compositeResult.failed += dirResult.failed;
                compositeResult.audioFiles += dirResult.audioFiles;
            } catch (Exception e) {
                compositeResult.failed++;
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        long elapsed = System.nanoTime() - processStartNanos;

        if (firstError != null && compositeResult.processed == 0) {
            telemetry.recordProcessFailed(elapsed);
            recordDuration("music.scan.dir.process.duration", elapsed,
                    "task_type", taskType.name(), "result", "ERROR");
            return DirectoryTaskOutcome.failed(firstDirInfo, firstDirPathMd5, firstError);
        }

        telemetry.recordProcessSuccess(elapsed);
        recordDuration("music.scan.dir.process.duration", elapsed,
                "task_type", taskType.name(), "result", "OK");

        // Create a merged outcome; we need to apply individual outcomes per entry
        // in applyDirectoryTaskOutcome, so we return a special composite.
        return DirectoryTaskOutcome.merged(entries, compositeResult);
    }

    private int countAudioFiles(List<WebDavFileObject> files, Set<String> supportedExtensions) {
        int count = 0;
        for (WebDavFileObject file : files) {
            String relativePath = normalizeRelativePath(file.getRelativePath());
            if (StringUtils.hasText(relativePath) && isAudioFile(relativePath, supportedExtensions)) {
                count++;
            }
        }
        return count;
    }

    // ── Detailed final summary ──────────────────────────────

    private void logDetailedSummary(Long taskId, WebDavConfigEntity config, TaskType taskType,
                                     ScanResult result, ScanProgressTracker tracker, long elapsedMs) {
        int dirsTotal = tracker.getTotalDirectoriesDiscovered();
        int dirsProcessed = tracker.getCompletedDirectories() - tracker.getSkippedDirectories();
        int dirsSkipped = tracker.getSkippedDirectories();
        double avgSpeed = elapsedMs > 0 ? result.getAudioFiles() * 1000.0 / elapsedMs : 0;

        log.info("\n========== SCAN COMPLETE ==========\n"
                        + "  Task ID:        {}\n"
                        + "  Config:         {} (id={})\n"
                        + "  Type:           {}\n"
                        + "  Duration:       {}\n"
                        + "  Directories:    {} total ({} skipped, {} processed)\n"
                        + "  Files:          {} audio / {} total discovered\n"
                        + "    Added:        {}\n"
                        + "    Updated:      {}\n"
                        + "    Skipped:      {} (unchanged)\n"
                        + "    Failed:       {}\n"
                        + "    Deleted:      {}\n"
                        + "    Deduplicated: {}\n"
                        + "  Speed:          {} files/s avg\n"
                        + "====================================",
                taskId,
                config.getName(), config.getId(),
                taskType.name(),
                formatElapsed(elapsedMs),
                dirsTotal, dirsSkipped, dirsProcessed,
                result.getAudioFiles(), tracker.getTotalFilesDiscovered(),
                result.getAddedCount(),
                result.getUpdatedCount(),
                tracker.getFilesSkipped(),
                result.getFailedCount(),
                result.getDeletedCount(),
                result.getDeduplicatedCount(),
                String.format(Locale.ROOT, "%.1f", avgSpeed));
    }

    // --- Inner classes ---

    private static class SmallDirEntry {
        final WebDavDirectoryInfo dirInfo;
        final String dirPathMd5;
        final String coverUrl;

        SmallDirEntry(WebDavDirectoryInfo dirInfo, String dirPathMd5, String coverUrl) {
            this.dirInfo = dirInfo;
            this.dirPathMd5 = dirPathMd5;
            this.coverUrl = coverUrl;
        }
    }

    private static class ScanTelemetry {
        private final Long taskId;
        private final Long configId;
        private final String taskType;

        private final LongAdder listOkCount = new LongAdder();
        private final LongAdder listErrCount = new LongAdder();
        private final LongAdder listTotalNanos = new LongAdder();

        private final LongAdder processOkCount = new LongAdder();
        private final LongAdder processErrCount = new LongAdder();
        private final LongAdder processTotalNanos = new LongAdder();

        private final LongAdder batchUpsertCalls = new LongAdder();
        private final LongAdder batchUpsertFailCalls = new LongAdder();
        private final LongAdder batchUpsertRows = new LongAdder();
        private final LongAdder batchUpsertTotalNanos = new LongAdder();

        private final LongAdder touchByMd5Calls = new LongAdder();
        private final LongAdder touchByMd5FailCalls = new LongAdder();
        private final LongAdder touchByMd5Rows = new LongAdder();
        private final LongAdder touchByMd5TotalNanos = new LongAdder();

        private final LongAdder touchByPrefixCalls = new LongAdder();
        private final LongAdder touchByPrefixFailCalls = new LongAdder();
        private final LongAdder touchByPrefixTotalNanos = new LongAdder();

        private final LongAdder seenInsertCalls = new LongAdder();
        private final LongAdder seenInsertFailCalls = new LongAdder();
        private final LongAdder seenInsertRows = new LongAdder();
        private final LongAdder seenInsertTotalNanos = new LongAdder();

        private final LongAdder signatureCalls = new LongAdder();
        private final LongAdder signatureFailCalls = new LongAdder();
        private final LongAdder signatureTotalNanos = new LongAdder();

        private final LongAdder checkpointCalls = new LongAdder();
        private final LongAdder checkpointFailCalls = new LongAdder();
        private final LongAdder checkpointTotalNanos = new LongAdder();

        private final LongAdder dedupCalls = new LongAdder();
        private final LongAdder dedupAffectedRows = new LongAdder();
        private final LongAdder dedupTotalNanos = new LongAdder();

        private ScanTelemetry(Long taskId, Long configId, String taskType) {
            this.taskId = taskId;
            this.configId = configId;
            this.taskType = taskType;
        }

        private void recordListSuccess(long nanos) {
            listOkCount.increment();
            listTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordListFailed(long nanos) {
            listErrCount.increment();
            listTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordProcessSuccess(long nanos) {
            processOkCount.increment();
            processTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordProcessFailed(long nanos) {
            processErrCount.increment();
            processTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordBatchUpsert(long nanos, int rows) {
            batchUpsertCalls.increment();
            batchUpsertRows.add(Math.max(0, rows));
            batchUpsertTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordBatchUpsertFailed() {
            batchUpsertFailCalls.increment();
        }

        private void recordTouchUpdate(long nanos, int rows) {
            touchByMd5Calls.increment();
            touchByMd5Rows.add(Math.max(0, rows));
            touchByMd5TotalNanos.add(Math.max(0L, nanos));
        }

        private void recordTouchUpdateFailed() {
            touchByMd5FailCalls.increment();
        }

        private void recordTouchByPrefix(long nanos) {
            touchByPrefixCalls.increment();
            touchByPrefixTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordTouchByPrefixFailed() {
            touchByPrefixFailCalls.increment();
        }

        private void recordSeenInsert(long nanos, int rows) {
            seenInsertCalls.increment();
            seenInsertRows.add(Math.max(0, rows));
            seenInsertTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordSeenInsertFailed() {
            seenInsertFailCalls.increment();
        }

        private void recordSignatureUpdate(long nanos) {
            signatureCalls.increment();
            signatureTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordSignatureUpdateFailed() {
            signatureFailCalls.increment();
        }

        private void recordCheckpointUpdate(long nanos) {
            checkpointCalls.increment();
            checkpointTotalNanos.add(Math.max(0L, nanos));
        }

        private void recordCheckpointUpdateFailed() {
            checkpointFailCalls.increment();
        }

        private void recordDedup(long nanos, int affectedRows) {
            dedupCalls.increment();
            dedupAffectedRows.add(Math.max(0, affectedRows));
            dedupTotalNanos.add(Math.max(0L, nanos));
        }

        private void logSummary(long elapsedMs, ScanResult result) {
            log.info("SCAN_STAGE_SUMMARY taskId={} configId={} taskType={} elapsedMs={} "
                            + "listOk={} listErr={} listAvgMs={} "
                            + "procOk={} procErr={} procAvgMs={} "
                            + "upsertCalls={} upsertFail={} upsertRows={} upsertAvgMs={} "
                            + "touchMd5Calls={} touchMd5Fail={} touchMd5Rows={} touchMd5AvgMs={} "
                            + "touchPrefixCalls={} touchPrefixFail={} touchPrefixAvgMs={} "
                            + "seenCalls={} seenFail={} seenRows={} seenAvgMs={} "
                            + "sigCalls={} sigFail={} sigAvgMs={} "
                            + "ckptCalls={} ckptFail={} ckptAvgMs={} "
                            + "dedupCalls={} dedupRows={} dedupAvgMs={} "
                            + "added={} updated={} deleted={} failed={}",
                    taskId, configId, taskType, elapsedMs,
                    listOkCount.sum(), listErrCount.sum(), avgMs(listTotalNanos, add(listOkCount, listErrCount)),
                    processOkCount.sum(), processErrCount.sum(), avgMs(processTotalNanos, add(processOkCount, processErrCount)),
                    batchUpsertCalls.sum(), batchUpsertFailCalls.sum(), batchUpsertRows.sum(),
                    avgMs(batchUpsertTotalNanos, batchUpsertCalls),
                    touchByMd5Calls.sum(), touchByMd5FailCalls.sum(), touchByMd5Rows.sum(),
                    avgMs(touchByMd5TotalNanos, touchByMd5Calls),
                    touchByPrefixCalls.sum(), touchByPrefixFailCalls.sum(),
                    avgMs(touchByPrefixTotalNanos, touchByPrefixCalls),
                    seenInsertCalls.sum(), seenInsertFailCalls.sum(), seenInsertRows.sum(),
                    avgMs(seenInsertTotalNanos, seenInsertCalls),
                    signatureCalls.sum(), signatureFailCalls.sum(), avgMs(signatureTotalNanos, signatureCalls),
                    checkpointCalls.sum(), checkpointFailCalls.sum(), avgMs(checkpointTotalNanos, checkpointCalls),
                    dedupCalls.sum(), dedupAffectedRows.sum(), avgMs(dedupTotalNanos, dedupCalls),
                    result.getAddedCount(), result.getUpdatedCount(), result.getDeletedCount(), result.getFailedCount());
        }

        private long add(LongAdder left, LongAdder right) {
            return left.sum() + right.sum();
        }

        private String avgMs(LongAdder totalNanos, LongAdder calls) {
            return avgMs(totalNanos, calls.sum());
        }

        private String avgMs(LongAdder totalNanos, long calls) {
            if (calls <= 0) {
                return "0.00";
            }
            double ms = totalNanos.sum() / 1_000_000.0D / calls;
            return String.format(Locale.ROOT, "%.2f", ms);
        }
    }

    private static class DirectoryListOutcome {
        private final String dirUrl;
        private final WebDavDirectoryInfo dirInfo;
        private final Exception error;

        private DirectoryListOutcome(String dirUrl, WebDavDirectoryInfo dirInfo, Exception error) {
            this.dirUrl = dirUrl;
            this.dirInfo = dirInfo;
            this.error = error;
        }

        private static DirectoryListOutcome success(String dirUrl, WebDavDirectoryInfo dirInfo) {
            return new DirectoryListOutcome(dirUrl, dirInfo, null);
        }

        private static DirectoryListOutcome failed(String dirUrl, Exception error) {
            return new DirectoryListOutcome(dirUrl, null, error);
        }
    }

    private static class DirectoryTaskOutcome {
        private final WebDavDirectoryInfo dirInfo;
        private final String dirRelativePath;
        private final String dirPathMd5;
        private final int fileCount;
        private final DirProcessResult dirResult;
        private final Exception error;
        /** Non-null only for merged small-directory batches */
        private final List<SmallDirEntry> mergedEntries;

        private DirectoryTaskOutcome(WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                     DirProcessResult dirResult, Exception error,
                                     List<SmallDirEntry> mergedEntries) {
            this.dirInfo = dirInfo;
            this.dirRelativePath = dirInfo == null ? "" : dirInfo.getRelativePath();
            this.dirPathMd5 = dirPathMd5;
            this.fileCount = dirInfo == null || dirInfo.getFiles() == null ? 0 : dirInfo.getFiles().size();
            this.dirResult = dirResult;
            this.error = error;
            this.mergedEntries = mergedEntries;
        }

        private static DirectoryTaskOutcome success(WebDavDirectoryInfo dirInfo, String dirPathMd5,
                                                    DirProcessResult dirResult) {
            return new DirectoryTaskOutcome(dirInfo, dirPathMd5, dirResult, null, null);
        }

        private static DirectoryTaskOutcome failed(WebDavDirectoryInfo dirInfo, String dirPathMd5, Exception error) {
            return new DirectoryTaskOutcome(dirInfo, dirPathMd5, null, error, null);
        }

        private static DirectoryTaskOutcome merged(List<SmallDirEntry> entries, DirProcessResult compositeResult) {
            WebDavDirectoryInfo firstInfo = entries.get(0).dirInfo;
            String firstMd5 = entries.get(0).dirPathMd5;
            return new DirectoryTaskOutcome(firstInfo, firstMd5, compositeResult, null, entries);
        }

        private boolean isMerged() {
            return mergedEntries != null && !mergedEntries.isEmpty();
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
