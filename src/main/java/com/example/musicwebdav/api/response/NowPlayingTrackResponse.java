package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NowPlayingTrackResponse {

    private Long trackId;
    private String title;
    private String artist;
    private String album;
    private Integer durationSec;
    private Integer progressSec;
}
