package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddPlaylistTracksResponse {

    private Long playlistId;
    private Integer requestedCount;
    private Integer addedCount;
    private Integer duplicateCount;
    private Integer trackCount;
}
