package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An artist entry for the FAVORITE_ARTISTS shelf.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShelfArtistResponse {

    /** Artist name. */
    private String artist;

    /** Total number of tracks by this artist. */
    private Integer trackCount;

    /** ID of a representative track (for cover art). */
    private Long coverTrackId;
}
