package com.example.musicwebdav.api.request;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PlayEventRequest {

    @NotNull(message = "eventType is required")
    @Pattern(regexp = "PLAY_START|PLAY_COMPLETE|SKIP", message = "eventType must be PLAY_START, PLAY_COMPLETE, or SKIP")
    private String eventType;

    @NotNull(message = "durationSec is required")
    @Min(value = 0, message = "durationSec must be >= 0")
    private Integer durationSec;
}
