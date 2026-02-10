package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistTrackOperationResponse {

    private Long playlistId;
    private Integer requestedCount;
    private Integer affectedCount;
    private Integer skippedCount;
    private Integer trackCount;
}
