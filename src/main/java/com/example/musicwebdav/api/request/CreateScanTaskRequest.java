package com.example.musicwebdav.api.request;

import com.example.musicwebdav.domain.enumtype.TaskType;
import javax.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateScanTaskRequest {

    @NotNull
    private TaskType taskType;

    @NotNull
    private Long configId;
}
