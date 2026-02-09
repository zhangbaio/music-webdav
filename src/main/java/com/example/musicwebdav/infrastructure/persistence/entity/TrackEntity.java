package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TrackEntity {

    private Long id;

    private Long sourceConfigId;

    private String sourcePath;

    private String sourcePathMd5;

    private String sourceEtag;

    private LocalDateTime sourceLastModified;

    private Long sourceSize;

    private String mimeType;

    private String contentHash;

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

    private Integer hasCover;

    private String coverArtUrl;

    private Integer isDeleted;

    private Long lastScanTaskId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
