package com.example.musicwebdav.domain.model;

import lombok.Data;

@Data
public class AudioMetadata {

    private String title;

    private String artist;

    private String album;

    private String albumArtist;

    private Integer trackNo;

    private Integer discNo;

    private Integer year;

    private String genre;

    private Integer durationSec;

    private Integer bitrate;

    private Integer sampleRate;

    private Integer channels;

    private Boolean hasCover;
}
