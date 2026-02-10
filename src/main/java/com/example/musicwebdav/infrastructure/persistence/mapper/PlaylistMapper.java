package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlaylistMapper {

    @Insert("INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count) "
            + "VALUES(#{name}, #{playlistType}, #{systemCode}, #{isDeleted}, #{trackCount})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PlaylistEntity entity);

    @Insert("INSERT INTO playlist(name, playlist_type, system_code, is_deleted, track_count) "
            + "SELECT #{name}, 'SYSTEM', #{systemCode}, 0, 0 "
            + "WHERE NOT EXISTS (SELECT 1 FROM playlist WHERE system_code = #{systemCode} AND is_deleted = 0)")
    int insertSystemIfAbsent(@Param("name") String name, @Param("systemCode") String systemCode);

    @Select("SELECT id, name, playlist_type, system_code, is_deleted, track_count, created_at, updated_at "
            + "FROM playlist WHERE id = #{id} AND is_deleted = 0")
    PlaylistEntity selectActiveById(@Param("id") Long id);

    @Select("SELECT id, name, playlist_type, system_code, is_deleted, track_count, created_at, updated_at "
            + "FROM playlist WHERE system_code = #{systemCode} AND is_deleted = 0 LIMIT 1")
    PlaylistEntity selectBySystemCode(@Param("systemCode") String systemCode);

    @Select("SELECT id, name, playlist_type, system_code, is_deleted, track_count, created_at, updated_at "
            + "FROM playlist WHERE is_deleted = 0 "
            + "ORDER BY CASE WHEN playlist_type = 'SYSTEM' THEN 0 ELSE 1 END, updated_at DESC, id DESC")
    List<PlaylistEntity> selectAllActive();

    @Select("SELECT COUNT(1) FROM playlist WHERE name = #{name} AND is_deleted = 0")
    int countByName(@Param("name") String name);

    @Update("UPDATE playlist SET name = #{name}, updated_at = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0 AND playlist_type = 'NORMAL'")
    int renameNormal(@Param("id") Long id, @Param("name") String name);

    @Update("UPDATE playlist SET is_deleted = 1, updated_at = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0 AND playlist_type = 'NORMAL'")
    int softDeleteNormal(@Param("id") Long id);

    @Update("UPDATE playlist p SET p.track_count = ("
            + "SELECT COUNT(1) FROM playlist_track pt "
            + "INNER JOIN track t ON t.id = pt.track_id "
            + "WHERE pt.playlist_id = #{playlistId} AND t.is_deleted = 0"
            + "), p.updated_at = NOW() "
            + "WHERE p.id = #{playlistId} AND p.is_deleted = 0")
    int refreshTrackCount(@Param("playlistId") Long playlistId);
}
