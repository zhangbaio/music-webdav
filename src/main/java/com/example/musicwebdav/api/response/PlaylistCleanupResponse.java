package com.example.musicwebdav.api.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistCleanupResponse {

    private Integer removedByDeletedPlaylist;
    private Integer removedByDeletedTrack;
    private Integer normalizedPlaylistCount;
    private Integer refreshedPlaylistCount;
    private Boolean normalizeOrderNo;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
