package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackResponse {

    private Long id;
    private String title;
    private String artist;
    private String album;
    private String sourcePath;
    private Integer durationSec;
}
