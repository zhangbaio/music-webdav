package com.example.musicwebdav.application.job;

import com.example.musicwebdav.api.request.CreateScanTaskRequest;
import com.example.musicwebdav.common.config.AppScanProperties;
import com.example.musicwebdav.common.exception.BusinessException;
import com.example.musicwebdav.domain.enumtype.TaskType;
import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.WebDavConfigMapper;
import com.example.musicwebdav.application.service.ScanTaskService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IncrementalScanJob {

    private static final Logger log = LoggerFactory.getLogger(IncrementalScanJob.class);

    private final AppScanProperties appScanProperties;
    private final WebDavConfigMapper webDavConfigMapper;
    private final ScanTaskService scanTaskService;

    public IncrementalScanJob(AppScanProperties appScanProperties,
                              WebDavConfigMapper webDavConfigMapper,
                              ScanTaskService scanTaskService) {
        this.appScanProperties = appScanProperties;
        this.webDavConfigMapper = webDavConfigMapper;
        this.scanTaskService = scanTaskService;
    }

    @Scheduled(cron = "${app.scan.incremental-cron:0 0 3 * * ?}")
    public void run() {
        List<WebDavConfigEntity> configs = webDavConfigMapper.selectEnabled();
        if (configs == null || configs.isEmpty()) {
            log.debug("Incremental scan skipped: no enabled WebDAV config");
            return;
        }
        log.info("Incremental scan schedule triggered, configCount={}, cron={}",
                configs.size(), appScanProperties.getIncrementalCron());
        for (WebDavConfigEntity config : configs) {
            CreateScanTaskRequest request = new CreateScanTaskRequest();
            request.setTaskType(TaskType.INCREMENTAL);
            request.setConfigId(config.getId());
            try {
                scanTaskService.createTask(request);
                log.info("Incremental scan task created, configId={}, configName={}",
                        config.getId(), config.getName());
            } catch (BusinessException e) {
                if ("409".equals(e.getCode())) {
                    log.info("Incremental scan skipped due to active task, configId={}, configName={}",
                            config.getId(), config.getName());
                } else {
                    log.warn("Incremental scan task create failed, configId={}, configName={}, code={}, msg={}",
                            config.getId(), config.getName(), e.getCode(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("Incremental scan task create failed unexpectedly, configId={}, configName={}",
                        config.getId(), config.getName(), e);
            }
        }
    }
}
