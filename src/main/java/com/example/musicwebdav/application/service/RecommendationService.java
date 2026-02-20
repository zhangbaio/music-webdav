package com.example.musicwebdav.application.service;

import com.example.musicwebdav.api.response.ShelfAlbumResponse;
import com.example.musicwebdav.api.response.ShelfArtistResponse;
import com.example.musicwebdav.api.response.ShelfResponse;
import com.example.musicwebdav.api.response.TrackResponse;
import com.example.musicwebdav.domain.ShelfType;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import com.example.musicwebdav.infrastructure.persistence.mapper.PlayEventMapper;
import com.example.musicwebdav.infrastructure.persistence.mapper.TrackMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds recommendation shelves for the Browse page.
 * <p>
 * All queries are real-time (no cache table). For a personal music library
 * of a few thousand tracks this is more than fast enough.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    /** Look-back window for play-event based shelves. */
    private static final int HOT_DAYS = 30;
    /** Look-back window for REDISCOVER: tracks not played in this many days. */
    private static final int REDISCOVER_DAYS = 60;
    /** Default limit per shelf. */
    private static final int SHELF_LIMIT = 20;
    /** Max genres to mix for GENRE_MIX. */
    private static final int GENRE_MIX_TOP_N = 3;

    private final PlayEventMapper playEventMapper;
    private final TrackMapper trackMapper;

    public RecommendationService(PlayEventMapper playEventMapper, TrackMapper trackMapper) {
        this.playEventMapper = playEventMapper;
        this.trackMapper = trackMapper;
    }

    /**
     * Build all recommendation shelves. Each shelf is computed independently;
     * if a shelf has no data it is omitted from the result.
     */
    public List<ShelfResponse> buildAllShelves() {
        List<ShelfResponse> shelves = new ArrayList<>();

        tryAdd(shelves, buildHotTracks());
        tryAdd(shelves, buildRecentAdded());
        tryAdd(shelves, buildRecentAlbums());
        tryAdd(shelves, buildListenAgain());
        tryAdd(shelves, buildDiscovery());
        tryAdd(shelves, buildFavoriteArtists());
        tryAdd(shelves, buildGenreMix());
        tryAdd(shelves, buildRediscover());

        return shelves;
    }

    // ------------------------------------------------------------------
    // Individual shelf builders
    // ------------------------------------------------------------------

    private ShelfResponse buildListenAgain() {
        try {
            List<TrackEntity> tracks = playEventMapper.selectListenAgain(SHELF_LIMIT);
            if (tracks.isEmpty()) return null;
            return ShelfResponse.ofTracks(
                    ShelfType.LISTEN_AGAIN.name(),
                    "重温经典",
                    toTrackResponses(tracks)
            );
        } catch (Exception e) {
            log.warn("Failed to build LISTEN_AGAIN shelf", e);
            return null;
        }
    }

    private ShelfResponse buildDiscovery() {
        try {
            List<TrackEntity> tracks = playEventMapper.selectDiscovery(SHELF_LIMIT);
            if (tracks.isEmpty()) return null;
            return ShelfResponse.ofTracks(
                    ShelfType.DISCOVERY.name(),
                    "发现新音乐",
                    toTrackResponses(tracks)
            );
        } catch (Exception e) {
            log.warn("Failed to build DISCOVERY shelf", e);
            return null;
        }
    }

    private ShelfResponse buildHotTracks() {
        try {
            List<TrackEntity> tracks = playEventMapper.selectHotTracks(HOT_DAYS, SHELF_LIMIT);
            if (tracks.isEmpty()) return null;
            return ShelfResponse.ofTracks(
                    ShelfType.HOT_TRACKS.name(),
                    "正在流行",
                    toTrackResponses(tracks)
            );
        } catch (Exception e) {
            log.warn("Failed to build HOT_TRACKS shelf", e);
            return null;
        }
    }

    private ShelfResponse buildRecentAdded() {
        try {
            List<TrackEntity> tracks = trackMapper.selectRecentlyAdded(SHELF_LIMIT);
            if (tracks.isEmpty()) return null;
            return ShelfResponse.ofTracks(
                    ShelfType.RECENT_ADDED.name(),
                    "最新歌曲",
                    toTrackResponses(tracks)
            );
        } catch (Exception e) {
            log.warn("Failed to build RECENT_ADDED shelf", e);
            return null;
        }
    }

    private ShelfResponse buildRecentAlbums() {
        try {
            List<Map<String, Object>> rows = trackMapper.selectRecentAlbums(SHELF_LIMIT);
            if (rows.isEmpty()) return null;

            List<ShelfAlbumResponse> albums = rows.stream()
                    .map(row -> new ShelfAlbumResponse(
                            asString(row.get("album")),
                            asString(row.get("artist")),
                            asInt(row.get("trackCount")),
                            asLong(row.get("coverTrackId")),
                            asIntOrNull(row.get("year"))
                    ))
                    .collect(Collectors.toList());

            return ShelfResponse.ofAlbums(
                    ShelfType.RECENT_ALBUMS.name(),
                    "最新专辑",
                    albums
            );
        } catch (Exception e) {
            log.warn("Failed to build RECENT_ALBUMS shelf", e);
            return null;
        }
    }

    private ShelfResponse buildFavoriteArtists() {
        try {
            List<String> artists = playEventMapper.selectTopArtists(HOT_DAYS, SHELF_LIMIT);
            if (artists.isEmpty()) return null;

            List<ShelfArtistResponse> result = new ArrayList<>();
            for (String artist : artists) {
                Map<String, Object> info = trackMapper.selectArtistInfo(artist);
                if (info == null) continue;
                result.add(new ShelfArtistResponse(
                        artist,
                        asInt(info.get("trackCount")),
                        asLong(info.get("coverTrackId"))
                ));
            }

            if (result.isEmpty()) return null;
            return ShelfResponse.ofArtists(
                    ShelfType.FAVORITE_ARTISTS.name(),
                    "常听艺人",
                    result
            );
        } catch (Exception e) {
            log.warn("Failed to build FAVORITE_ARTISTS shelf", e);
            return null;
        }
    }

    private ShelfResponse buildGenreMix() {
        try {
            List<String> genres = playEventMapper.selectTopGenres(HOT_DAYS, GENRE_MIX_TOP_N);
            if (genres.isEmpty()) {
                // Fallback: random tracks if no play history
                return null;
            }

            int perGenre = Math.max(1, SHELF_LIMIT / genres.size());
            List<TrackEntity> mixed = new ArrayList<>();
            for (String genre : genres) {
                mixed.addAll(trackMapper.selectByGenreRandom(genre, perGenre));
            }

            if (mixed.isEmpty()) return null;
            // Shuffle to mix genres together
            Collections.shuffle(mixed);

            return ShelfResponse.ofTracks(
                    ShelfType.GENRE_MIX.name(),
                    "风格混搭",
                    toTrackResponses(mixed.size() > SHELF_LIMIT ? mixed.subList(0, SHELF_LIMIT) : mixed)
            );
        } catch (Exception e) {
            log.warn("Failed to build GENRE_MIX shelf", e);
            return null;
        }
    }

    private ShelfResponse buildRediscover() {
        try {
            List<Long> recentIds = playEventMapper.selectRecentlyPlayedTrackIds(REDISCOVER_DAYS);
            List<TrackEntity> tracks = trackMapper.selectRandomExcluding(
                    recentIds.isEmpty() ? Collections.emptyList() : recentIds,
                    SHELF_LIMIT
            );
            if (tracks.isEmpty()) return null;
            return ShelfResponse.ofTracks(
                    ShelfType.REDISCOVER.name(),
                    "重新发现",
                    toTrackResponses(tracks)
            );
        } catch (Exception e) {
            log.warn("Failed to build REDISCOVER shelf", e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void tryAdd(List<ShelfResponse> shelves, ShelfResponse shelf) {
        if (shelf != null) {
            shelves.add(shelf);
        }
    }

    private static List<TrackResponse> toTrackResponses(List<TrackEntity> entities) {
        return entities.stream()
                .map(e -> new TrackResponse(
                        e.getId(),
                        e.getTitle(),
                        e.getArtist(),
                        e.getAlbum(),
                        e.getSourcePath(),
                        e.getDurationSec(),
                        e.getHasLyric()
                ))
                .collect(Collectors.toList());
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int asInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    private static long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return 0L;
    }

    private static Integer asIntOrNull(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }
}
