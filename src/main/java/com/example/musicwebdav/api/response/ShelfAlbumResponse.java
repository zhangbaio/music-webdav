package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A distinct album entry for the RECENT_ALBUMS shelf.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShelfAlbumResponse {

    /** Album name. */
    private String album;

    /** Primary artist of the album. */
    private String artist;

    /** Number of tracks in this album. */
    private Integer trackCount;

    /** ID of a representative track (for cover art). */
    private Long coverTrackId;

    /** Year the album was released (may be null). */
    private Integer year;
}
