package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlayEventEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlayEventMapper {

    void insert(PlayEventEntity entity);

    /**
     * Hot tracks: compute a heat score per track from play events in the last N days.
     * <p>
     * Heat formula: SUM(weight) / LN(avg_hours_since_event + 2)
     * <ul>
     *   <li>PLAY_COMPLETE = 3</li>
     *   <li>PLAY_START = 1</li>
     *   <li>SKIP = -1</li>
     * </ul>
     * Only considers non-deleted tracks. Returns tracks ordered by heat DESC.
     */
    List<TrackEntity> selectHotTracks(@Param("days") int days, @Param("limit") int limit, @Param("userId") Long userId);

    /**
     * Most-played artists from play events in the last N days.
     * Returns artist names ordered by weighted play count DESC.
     */
    List<String> selectTopArtists(@Param("days") int days, @Param("limit") int limit, @Param("userId") Long userId);

    /**
     * Most-played genres from play events in the last N days.
     * Returns genre names ordered by weighted play count DESC.
     */
    List<String> selectTopGenres(@Param("days") int days, @Param("limit") int limit, @Param("userId") Long userId);

    /**
     * Track IDs that have been played at least once in the last N days.
     * Used to find "un-played" tracks for REDISCOVER shelf.
     */
    List<Long> selectRecentlyPlayedTrackIds(@Param("days") int days, @Param("userId") Long userId);

    /**
     * Recently played tracks ordered by latest play timestamp (deduplicated by track).
     */
    List<TrackEntity> selectRecentlyPlayedTracks(@Param("offset") int offset, @Param("limit") int limit, @Param("userId") Long userId);

    /**
     * Count distinct tracks in recently-played stream.
     */
    long countRecentlyPlayedTracks(@Param("userId") Long userId);

    /**
     * Listen again: tracks with high play count over all time.
     */
    List<TrackEntity> selectListenAgain(@Param("limit") int limit, @Param("userId") Long userId);

    /**
     * Discovery: tracks from favorite genres/artists that haven't been played recently.
     */
    List<TrackEntity> selectDiscovery(@Param("limit") int limit, @Param("userId") Long userId);
}
