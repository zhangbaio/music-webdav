package com.example.musicwebdav.api.request;

import java.util.List;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Data;

@Data
public class PlaybackControlRequest {

    /**
     * pause | resume | previous | next
     */
    @NotBlank
    private String command;

    private Long currentTrackId;

    @Size(max = 200)
    private List<Long> queueTrackIds;

    @Min(0)
    private Integer progressSec;
}
