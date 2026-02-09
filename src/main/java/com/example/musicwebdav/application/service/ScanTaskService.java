package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.request.CreateScanTaskRequest;
import com.example.musicwebdav.api.response.CreateScanTaskResponse;
import com.example.musicwebdav.api.response.ScanTaskDetailResponse;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.domain.enumtype.TaskType;
import com.example.musicwebdav.domain.enumtype.TaskStatus;
import com.example.musicwebdav.infrastructure.persistence.entity.ScanTaskEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.ScanTaskMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ScanTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScanTaskService.class);

    private final ScanTaskMapper scanTaskMapper;
    private final WebDavConfigMapper webDavConfigMapper;
    private final FullScanService fullScanService;
    private final ExecutorService scanTaskExecutor;

    public ScanTaskService(ScanTaskMapper scanTaskMapper,
                           WebDavConfigMapper webDavConfigMapper,
                           FullScanService fullScanService,
                           ExecutorService scanTaskExecutor) {
        this.scanTaskMapper = scanTaskMapper;
        this.webDavConfigMapper = webDavConfigMapper;
        this.fullScanService = fullScanService;
        this.scanTaskExecutor = scanTaskExecutor;
    }

    public CreateScanTaskResponse createTask(CreateScanTaskRequest request) {
        WebDavConfigEntity config = webDavConfigMapper.selectById(request.getConfigId());
        if (config == null) {
            throw new BusinessException("404", "WebDAV配置不存在");
        }
        if (TaskType.FULL != request.getTaskType()) {
            throw new BusinessException("400", "当前版本仅支持 FULL 全量扫描");
        }

        ScanTaskEntity entity = new ScanTaskEntity();
        entity.setTaskType(request.getTaskType().name());
        entity.setStatus(TaskStatus.PENDING.name());
        entity.setConfigId(request.getConfigId());
        entity.setTotalFiles(0);
        entity.setAudioFiles(0);
        entity.setAddedCount(0);
        entity.setUpdatedCount(0);
        entity.setDeletedCount(0);
        entity.setFailedCount(0);
        scanTaskMapper.insert(entity);
        log.info("SCAN_TASK_CREATED taskId={} type={} configId={}", entity.getId(), entity.getTaskType(), entity.getConfigId());

        try {
            scanTaskExecutor.submit(() -> executeFullTask(entity.getId(), config));
        } catch (RejectedExecutionException e) {
            scanTaskMapper.markFailedBeforeRunning(
                    entity.getId(),
                    TaskStatus.FAILED.name(),
                    1,
                    "任务调度失败: " + truncate(e.getMessage(), 400));
            throw new BusinessException("TASK_EXECUTOR_REJECTED", "任务调度失败，请稍后重试");
        }

        return new CreateScanTaskResponse(entity.getId(), TaskStatus.PENDING.name());
    }

    public ScanTaskDetailResponse getTask(Long taskId) {
        ScanTaskEntity entity = scanTaskMapper.selectById(taskId);
        if (entity == null) {
            return null;
        }
        return toDetailResponse(entity);
    }

    public boolean cancelTask(Long taskId) {
        ScanTaskEntity entity = scanTaskMapper.selectById(taskId);
        if (entity == null) {
            return false;
        }
        int affected = scanTaskMapper.cancel(taskId, TaskStatus.CANCELED.name());
        if (affected > 0) {
            log.info("SCAN_TASK_CANCELED taskId={} fromStatus={}", taskId, entity.getStatus());
        } else {
            log.info("SCAN_TASK_CANCEL_IGNORED taskId={} currentStatus={}", taskId, entity.getStatus());
        }
        return true;
    }

    private void executeFullTask(Long taskId, WebDavConfigEntity config) {
        int runningUpdated = scanTaskMapper.markRunning(taskId, TaskStatus.RUNNING.name());
        if (runningUpdated == 0) {
            String currentStatus = scanTaskMapper.selectStatusById(taskId);
            log.info("SCAN_TASK_START_SKIPPED taskId={} currentStatus={}", taskId, currentStatus);
            return;
        }
        log.info("SCAN_TASK_RUNNING taskId={} type={} configId={}", taskId, TaskType.FULL.name(), config.getId());
        try {
            FullScanService.ScanStats stats = fullScanService.scan(taskId, config, () -> isCanceled(taskId));
            if (stats.isCanceled()) {
                scanTaskMapper.updateCanceledStats(
                        taskId,
                        stats.getTotalFiles(),
                        stats.getAudioFiles(),
                        stats.getAddedCount(),
                        stats.getUpdatedCount(),
                        stats.getDeletedCount(),
                        stats.getFailedCount(),
                        "任务被取消");
                log.info("SCAN_TASK_RESULT taskId={} status={} total={} audio={} added={} updated={} deleted={} failed={}",
                        taskId, TaskStatus.CANCELED.name(), stats.getTotalFiles(), stats.getAudioFiles(), stats.getAddedCount(),
                        stats.getUpdatedCount(), stats.getDeletedCount(), stats.getFailedCount());
                return;
            }
            String finalStatus = stats.getFailedCount() > 0 ? TaskStatus.PARTIAL_SUCCESS.name() : TaskStatus.SUCCESS.name();
            int finishedRows = scanTaskMapper.markFinished(
                    taskId,
                    finalStatus,
                    stats.getTotalFiles(),
                    stats.getAudioFiles(),
                    stats.getAddedCount(),
                    stats.getUpdatedCount(),
                    stats.getDeletedCount(),
                    stats.getFailedCount(),
                    null);
            if (finishedRows == 0) {
                if (isCanceled(taskId)) {
                    scanTaskMapper.updateCanceledStats(
                            taskId,
                            stats.getTotalFiles(),
                            stats.getAudioFiles(),
                            stats.getAddedCount(),
                            stats.getUpdatedCount(),
                            stats.getDeletedCount(),
                            stats.getFailedCount(),
                            "任务被取消");
                    log.info("SCAN_TASK_RESULT taskId={} status={} total={} audio={} added={} updated={} deleted={} failed={}",
                            taskId, TaskStatus.CANCELED.name(), stats.getTotalFiles(), stats.getAudioFiles(), stats.getAddedCount(),
                            stats.getUpdatedCount(), stats.getDeletedCount(), stats.getFailedCount());
                } else {
                    String currentStatus = scanTaskMapper.selectStatusById(taskId);
                    log.warn("SCAN_TASK_FINISH_REJECTED taskId={} expectStatus=RUNNING currentStatus={}", taskId, currentStatus);
                }
            } else {
                log.info("SCAN_TASK_RESULT taskId={} status={} total={} audio={} added={} updated={} deleted={} failed={}",
                        taskId, finalStatus, stats.getTotalFiles(), stats.getAudioFiles(), stats.getAddedCount(),
                        stats.getUpdatedCount(), stats.getDeletedCount(), stats.getFailedCount());
            }
        } catch (Exception e) {
            log.error("Full scan task failed, taskId={}", taskId, e);
            if (isCanceled(taskId)) {
                scanTaskMapper.updateCanceledStats(
                        taskId, 0, 0, 0, 0, 0, 1, "任务被取消");
            } else {
                scanTaskMapper.markFinished(
                        taskId,
                        TaskStatus.FAILED.name(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        1,
                        truncate(e.getMessage(), 1000));
            }
        }
    }

    private boolean isCanceled(Long taskId) {
        String status = scanTaskMapper.selectStatusById(taskId);
        return TaskStatus.CANCELED.name().equals(status);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private ScanTaskDetailResponse toDetailResponse(ScanTaskEntity entity) {
        return new ScanTaskDetailResponse(
                entity.getId(),
                entity.getTaskType(),
                entity.getStatus(),
                entity.getConfigId(),
                entity.getStartTime(),
                entity.getEndTime(),
                nullSafeInt(entity.getTotalFiles()),
                nullSafeInt(entity.getAudioFiles()),
                nullSafeInt(entity.getAddedCount()),
                nullSafeInt(entity.getUpdatedCount()),
                nullSafeInt(entity.getDeletedCount()),
                nullSafeInt(entity.getFailedCount()),
                entity.getErrorSummary()
        );
    }

    private int nullSafeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
