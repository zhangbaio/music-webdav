package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlayEventEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlayEventMapper {

    @Insert("INSERT INTO play_event (track_id, event_type, duration_sec) "
            + "VALUES (#{trackId}, #{eventType}, #{durationSec})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
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
    @Select("SELECT t.id, t.title, t.artist, t.album, t.source_path, t.duration_sec, t.has_lyric "
            + "FROM ("
            + "  SELECT pe.track_id, "
            + "    SUM(CASE pe.event_type "
            + "      WHEN 'PLAY_COMPLETE' THEN 3 "
            + "      WHEN 'PLAY_START' THEN 1 "
            + "      WHEN 'SKIP' THEN -1 "
            + "      ELSE 0 END"
            + "    ) / LN(AVG(TIMESTAMPDIFF(HOUR, pe.created_at, NOW())) + 2) AS heat "
            + "  FROM play_event pe "
            + "  WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) "
            + "  GROUP BY pe.track_id "
            + "  HAVING heat > 0 "
            + ") scored "
            + "INNER JOIN track t ON t.id = scored.track_id AND t.is_deleted = 0 "
            + "ORDER BY scored.heat DESC "
            + "LIMIT #{limit}")
    List<TrackEntity> selectHotTracks(@Param("days") int days, @Param("limit") int limit);

    /**
     * Most-played artists from play events in the last N days.
     * Returns artist names ordered by weighted play count DESC.
     */
    @Select("SELECT t.artist "
            + "FROM play_event pe "
            + "INNER JOIN track t ON t.id = pe.track_id AND t.is_deleted = 0 "
            + "WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) "
            + "  AND t.artist IS NOT NULL AND t.artist <> '' "
            + "GROUP BY t.artist "
            + "ORDER BY SUM(CASE pe.event_type "
            + "  WHEN 'PLAY_COMPLETE' THEN 3 "
            + "  WHEN 'PLAY_START' THEN 1 "
            + "  WHEN 'SKIP' THEN -1 "
            + "  ELSE 0 END) DESC "
            + "LIMIT #{limit}")
    List<String> selectTopArtists(@Param("days") int days, @Param("limit") int limit);

    /**
     * Most-played genres from play events in the last N days.
     * Returns genre names ordered by weighted play count DESC.
     */
    @Select("SELECT t.genre "
            + "FROM play_event pe "
            + "INNER JOIN track t ON t.id = pe.track_id AND t.is_deleted = 0 "
            + "WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) "
            + "  AND t.genre IS NOT NULL AND t.genre <> '' "
            + "GROUP BY t.genre "
            + "ORDER BY SUM(CASE pe.event_type "
            + "  WHEN 'PLAY_COMPLETE' THEN 3 "
            + "  WHEN 'PLAY_START' THEN 1 "
            + "  WHEN 'SKIP' THEN -1 "
            + "  ELSE 0 END) DESC "
            + "LIMIT #{limit}")
    List<String> selectTopGenres(@Param("days") int days, @Param("limit") int limit);

    /**
     * Track IDs that have been played at least once in the last N days.
     * Used to find "un-played" tracks for REDISCOVER shelf.
     */
    @Select("SELECT DISTINCT pe.track_id "
            + "FROM play_event pe "
            + "WHERE pe.created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    List<Long> selectRecentlyPlayedTrackIds(@Param("days") int days);

    /**
     * Recently played tracks ordered by latest play timestamp (deduplicated by track).
     */
    @Select("SELECT t.id, t.title, t.artist, t.album, t.source_path, t.duration_sec, t.has_lyric "
            + "FROM ("
            + "  SELECT pe.track_id, MAX(pe.created_at) AS last_played_at "
            + "  FROM play_event pe "
            + "  WHERE pe.event_type IN ('PLAY_START', 'PLAY_COMPLETE') "
            + "  GROUP BY pe.track_id "
            + ") rp "
            + "INNER JOIN track t ON t.id = rp.track_id AND t.is_deleted = 0 "
            + "ORDER BY rp.last_played_at DESC "
            + "LIMIT #{offset}, #{limit}")
    List<TrackEntity> selectRecentlyPlayedTracks(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * Count distinct tracks in recently-played stream.
     */
    @Select("SELECT COUNT(DISTINCT pe.track_id) "
            + "FROM play_event pe "
            + "INNER JOIN track t ON t.id = pe.track_id AND t.is_deleted = 0 "
            + "WHERE pe.event_type IN ('PLAY_START', 'PLAY_COMPLETE')")
    long countRecentlyPlayedTracks();
}
