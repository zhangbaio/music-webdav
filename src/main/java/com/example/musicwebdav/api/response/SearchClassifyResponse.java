package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchClassifyResponse {

    /**
     * SONG / ARTIST
     */
    private String mode;

    /**
     * 0.0 - 1.0
     */
    private Double confidence;

    /**
     * Inferred artist name when mode is ARTIST.
     */
    private String normalizedArtist;

    private Long allSongCount;
    private Long mineSongCount;
    private Long artistCount;
    private Long albumCount;
    private Long artistSongCount;
}
