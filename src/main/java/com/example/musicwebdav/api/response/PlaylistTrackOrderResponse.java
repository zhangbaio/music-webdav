package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistTrackOrderResponse {

    private Long playlistId;
    private Integer totalCount;
    private Integer updatedCount;
}
