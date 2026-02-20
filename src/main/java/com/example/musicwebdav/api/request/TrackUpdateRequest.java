package com.example.musicwebdav.api.request;

import lombok.Data;

@Data
public class TrackUpdateRequest {
    private String title;
    private String artist;
    private String album;
    private String albumArtist;
    private Integer trackNo;
    private Integer discNo;
    private Integer year;
    private String genre;
}
