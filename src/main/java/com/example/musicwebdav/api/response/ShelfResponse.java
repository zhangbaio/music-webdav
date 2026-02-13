package com.example.musicwebdav.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single recommendation shelf.
 * <p>
 * Exactly one of {@code tracks}, {@code albums}, or {@code artists} will be
 * populated depending on the shelf type.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShelfResponse {

    /** Machine-readable shelf type, e.g. "HOT_TRACKS". */
    private String shelfType;

    /** Human-readable shelf title, e.g. "正在流行". */
    private String title;

    /** Tracks in this shelf (for HOT_TRACKS, RECENT_ADDED, GENRE_MIX, REDISCOVER). */
    private List<TrackResponse> tracks;

    /** Albums in this shelf (for RECENT_ALBUMS). */
    private List<ShelfAlbumResponse> albums;

    /** Artists in this shelf (for FAVORITE_ARTISTS). */
    private List<ShelfArtistResponse> artists;

    public static ShelfResponse ofTracks(String shelfType, String title, List<TrackResponse> tracks) {
        return new ShelfResponse(shelfType, title, tracks, null, null);
    }

    public static ShelfResponse ofAlbums(String shelfType, String title, List<ShelfAlbumResponse> albums) {
        return new ShelfResponse(shelfType, title, null, albums, null);
    }

    public static ShelfResponse ofArtists(String shelfType, String title, List<ShelfArtistResponse> artists) {
        return new ShelfResponse(shelfType, title, null, null, artists);
    }
}
