package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistTrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlaylistTrackMapper {

    @Insert("INSERT INTO playlist_track(playlist_id, track_id, order_no) "
            + "VALUES(#{playlistId}, #{trackId}, #{orderNo}) "
            + "ON DUPLICATE KEY UPDATE updated_at = NOW()")
    int upsert(@Param("playlistId") Long playlistId,
               @Param("trackId") Long trackId,
               @Param("orderNo") Integer orderNo);

    @Delete("DELETE FROM playlist_track WHERE playlist_id = #{playlistId} AND track_id = #{trackId}")
    int deleteByPlaylistAndTrack(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    @Delete("DELETE FROM playlist_track WHERE playlist_id = #{playlistId}")
    int deleteByPlaylistId(@Param("playlistId") Long playlistId);

    @Delete({"<script>",
            "DELETE FROM playlist_track WHERE playlist_id = #{playlistId} AND track_id IN",
            "<foreach collection='trackIds' item='trackId' open='(' separator=',' close=')'>",
            "#{trackId}",
            "</foreach>",
            "</script>"})
    int batchDeleteByPlaylistAndTrackIds(@Param("playlistId") Long playlistId,
                                         @Param("trackIds") List<Long> trackIds);

    @Select("SELECT COUNT(1) FROM playlist_track WHERE playlist_id = #{playlistId} AND track_id = #{trackId}")
    int countByPlaylistAndTrack(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    @Select("SELECT IFNULL(MAX(order_no), 0) FROM playlist_track WHERE playlist_id = #{playlistId}")
    Integer selectMaxOrderNo(@Param("playlistId") Long playlistId);

    @Select("SELECT id, playlist_id, track_id, order_no, created_at, updated_at "
            + "FROM playlist_track WHERE playlist_id = #{playlistId} "
            + "ORDER BY order_no ASC, id ASC")
    List<PlaylistTrackEntity> selectByPlaylistIdOrdered(@Param("playlistId") Long playlistId);

    @Update("UPDATE playlist_track SET order_no = #{orderNo}, updated_at = NOW() WHERE id = #{id}")
    int updateOrderNoById(@Param("id") Long id, @Param("orderNo") Integer orderNo);

    @Delete("DELETE pt FROM playlist_track pt "
            + "LEFT JOIN playlist p ON p.id = pt.playlist_id "
            + "WHERE p.id IS NULL OR p.is_deleted = 1")
    int deleteByDeletedPlaylists();

    @Delete("DELETE pt FROM playlist_track pt "
            + "LEFT JOIN track t ON t.id = pt.track_id "
            + "WHERE t.id IS NULL OR t.is_deleted = 1")
    int deleteByDeletedTracks();

    @Select("SELECT t.id, t.title, t.artist, t.album, t.source_path, t.duration_sec, t.has_lyric "
            + "FROM playlist_track pt "
            + "INNER JOIN track t ON t.id = pt.track_id "
            + "WHERE pt.playlist_id = #{playlistId} AND t.is_deleted = 0 "
            + "ORDER BY pt.order_no ASC, pt.id ASC "
            + "LIMIT #{offset}, #{pageSize}")
    List<TrackEntity> selectTrackPage(@Param("playlistId") Long playlistId,
                                      @Param("offset") int offset,
                                      @Param("pageSize") int pageSize);

    @Select("SELECT COUNT(1) "
            + "FROM playlist_track pt "
            + "INNER JOIN track t ON t.id = pt.track_id "
            + "WHERE pt.playlist_id = #{playlistId} AND t.is_deleted = 0")
    long countTracks(@Param("playlistId") Long playlistId);

    @Select("<script>"
            + "SELECT t.id, t.title, t.artist, t.album, t.source_path, t.duration_sec, t.has_lyric "
            + "FROM ("
            + "  SELECT pt.track_id "
            + "  FROM playlist_track pt "
            + "  INNER JOIN playlist p ON p.id = pt.playlist_id "
            + "  WHERE p.is_deleted = 0 "
            + "  GROUP BY pt.track_id"
            + ") agg "
            + "INNER JOIN track t ON t.id = agg.track_id "
            + "WHERE t.is_deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND (t.title LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR t.artist LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR t.album LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + " ORDER BY "
            + "<choose>"
            + "  <when test='sortBy == \"title\"'>t.title</when>"
            + "  <when test='sortBy == \"artist\"'>t.artist</when>"
            + "  <when test='sortBy == \"album\"'>t.album</when>"
            + "  <when test='sortBy == \"year\"'>t.`year`</when>"
            + "  <when test='sortBy == \"created_at\"'>t.created_at</when>"
            + "  <otherwise>t.updated_at</otherwise>"
            + "</choose>"
            + " "
            + "<choose>"
            + "  <when test='sortOrder == \"ASC\"'>ASC</when>"
            + "  <otherwise>DESC</otherwise>"
            + "</choose>"
            + ", t.id DESC "
            + "LIMIT #{offset}, #{pageSize}"
            + "</script>")
    List<TrackEntity> selectAggregatedTrackPage(@Param("offset") int offset,
                                                @Param("pageSize") int pageSize,
                                                @Param("keyword") String keyword,
                                                @Param("sortBy") String sortBy,
                                                @Param("sortOrder") String sortOrder);

    @Select("<script>"
            + "SELECT COUNT(1) FROM ("
            + "  SELECT t.id "
            + "  FROM ("
            + "    SELECT pt.track_id "
            + "    FROM playlist_track pt "
            + "    INNER JOIN playlist p ON p.id = pt.playlist_id "
            + "    WHERE p.is_deleted = 0 "
            + "    GROUP BY pt.track_id"
            + "  ) agg "
            + "  INNER JOIN track t ON t.id = agg.track_id "
            + "  WHERE t.is_deleted = 0 "
            + "  <if test='keyword != null and keyword != \"\"'>"
            + "   AND (t.title LIKE CONCAT('%', #{keyword}, '%') "
            + "     OR t.artist LIKE CONCAT('%', #{keyword}, '%') "
            + "     OR t.album LIKE CONCAT('%', #{keyword}, '%'))"
            + "  </if>"
            + ") c"
            + "</script>")
    long countAggregatedTracks(@Param("keyword") String keyword);
}
