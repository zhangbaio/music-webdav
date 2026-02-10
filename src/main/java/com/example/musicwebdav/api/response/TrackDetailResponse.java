package com.example.musicwebdav.api.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackDetailResponse {

    private Long id;
    private Long sourceConfigId;
    private String sourcePath;
    private String sourceEtag;
    private LocalDateTime sourceLastModified;
    private Long sourceSize;
    private String mimeType;

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
    private Integer hasLyric;
    private String lyricPath;
    private LocalDateTime updatedAt;
}
