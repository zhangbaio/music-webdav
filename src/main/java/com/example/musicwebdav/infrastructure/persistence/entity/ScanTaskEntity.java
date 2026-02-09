package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ScanTaskEntity {

    private Long id;

    private String taskType;

    private String status;

    private Long configId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer totalFiles;

    private Integer audioFiles;

    private Integer addedCount;

    private Integer updatedCount;

    private Integer deletedCount;

    private Integer failedCount;

    private String errorSummary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
