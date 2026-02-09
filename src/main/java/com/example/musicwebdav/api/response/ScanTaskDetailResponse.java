package com.example.musicwebdav.api.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanTaskDetailResponse {

    private Long taskId;
    private String taskType;
    private String status;
    private Long configId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int totalFiles;
    private int audioFiles;
    private int addedCount;
    private int updatedCount;
    private int deletedCount;
    private int failedCount;
    private String errorSummary;
}
