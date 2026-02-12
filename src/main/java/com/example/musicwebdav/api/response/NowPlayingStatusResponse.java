package com.example.musicwebdav.api.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NowPlayingStatusResponse {

    private String state;
    private String lastCommand;
    private Long currentTrackId;
    private NowPlayingTrackResponse track;
    private List<Long> queueTrackIds;
    private boolean hasPrevious;
    private boolean hasNext;
    private Integer progressSec;
    private Long updatedAtEpochSecond;
}
