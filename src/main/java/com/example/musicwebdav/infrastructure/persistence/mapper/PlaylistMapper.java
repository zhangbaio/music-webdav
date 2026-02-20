package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlaylistMapper {

    int insert(PlaylistEntity entity);

    int insertSystemIfAbsent(@Param("name") String name, @Param("systemCode") String systemCode);

    PlaylistEntity selectActiveById(@Param("id") Long id);

    PlaylistEntity selectBySystemCode(@Param("systemCode") String systemCode);

    List<PlaylistEntity> selectAllActive();

    List<Long> selectAllActiveIds();

    Integer selectMaxSortNoOfNormal();

    int countByName(@Param("name") String name);

    int renameNormal(@Param("id") Long id, @Param("name") String name);

    int softDeleteNormal(@Param("id") Long id);

    int updateSortNoNormal(@Param("id") Long id, @Param("sortNo") Integer sortNo);

    int touchUpdatedAt(@Param("id") Long id);

    int refreshTrackCount(@Param("playlistId") Long playlistId);

    int refreshAllTrackCount();
}
