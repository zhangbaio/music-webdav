package com.example.musicwebdav.api.request;

import java.util.List;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class QueueReorderRequest {

    @NotNull
    @Min(0)
    private Integer fromIndex;

    @NotNull
    @Min(0)
    private Integer toIndex;

    private Long currentTrackId;

    @Size(max = 200)
    private List<Long> queueTrackIds;

    @Min(0)
    private Integer progressSec;

    @Min(0)
    private Long expectedUpdatedAtEpochSecond;
}
