package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class ScanCheckpointEntity {

    private Long id;

    private Long taskId;

    private String dirPath;

    private String dirPathMd5;

    private String status;

    private Integer fileCount;

    private Integer processedCount;

    private Integer failedCount;

    private String errorMessage;

    private LocalDateTime createdAt;
}
