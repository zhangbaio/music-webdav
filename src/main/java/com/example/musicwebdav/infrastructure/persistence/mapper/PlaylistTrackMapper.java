package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlaylistTrackEntity;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlaylistTrackMapper {

    int upsert(@Param("playlistId") Long playlistId,
               @Param("trackId") Long trackId,
               @Param("orderNo") Integer orderNo);

    int deleteByPlaylistAndTrack(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    int deleteByPlaylistId(@Param("playlistId") Long playlistId);

    int batchDeleteByPlaylistAndTrackIds(@Param("playlistId") Long playlistId,
                                         @Param("trackIds") List<Long> trackIds);

    int countByPlaylistAndTrack(@Param("playlistId") Long playlistId, @Param("trackId") Long trackId);

    Integer selectMaxOrderNo(@Param("playlistId") Long playlistId);

    List<PlaylistTrackEntity> selectByPlaylistIdOrdered(@Param("playlistId") Long playlistId);

    int updateOrderNoById(@Param("id") Long id, @Param("orderNo") Integer orderNo);

    int deleteByDeletedPlaylists();

    int deleteByDeletedTracks();

    List<TrackEntity> selectTrackPage(@Param("playlistId") Long playlistId,
                                      @Param("offset") int offset,
                                      @Param("pageSize") int pageSize);

    long countTracks(@Param("playlistId") Long playlistId);

    List<TrackEntity> selectAggregatedTrackPage(@Param("offset") int offset,
                                                @Param("pageSize") int pageSize,
                                                @Param("keyword") String keyword,
                                                @Param("sortBy") String sortBy,
                                                @Param("sortOrder") String sortOrder);

    long countAggregatedTracks(@Param("keyword") String keyword);
}
