package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    @Select("SELECT COUNT(1) FROM playlist_track WHERE playlist_id = #{playlistId} AND track_id = #{trackId}")
    int countByPlaylistAndTrack(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    @Select("SELECT IFNULL(MAX(order_no), 0) FROM playlist_track WHERE playlist_id = #{playlistId}")
    Integer selectMaxOrderNo(@Param("playlistId") Long playlistId);

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
}
