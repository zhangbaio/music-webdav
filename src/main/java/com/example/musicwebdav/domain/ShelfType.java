package com.example.musicwebdav.domain;

/**
 * Types of recommendation shelves shown on the Browse page.
 */
public enum ShelfType {

    /** Tracks with highest heat score (play events weighted by recency). */
    HOT_TRACKS,

    /** Most recently added tracks to the library. */
    RECENT_ADDED,

    /** Most recently added albums (distinct album + artist pairs). */
    RECENT_ALBUMS,

    /** Artists that the user has played or favorited most. */
    FAVORITE_ARTISTS,

    /** A mix of tracks from the user's most-played genres. */
    GENRE_MIX,

    /** Tracks that haven't been played in a long time. */
    REDISCOVER
}
