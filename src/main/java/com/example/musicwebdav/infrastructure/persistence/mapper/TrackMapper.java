package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.domain.model.DuplicateGroup;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrackMapper {

    int upsert(TrackEntity entity);

    TrackEntity selectByConfigAndPathMd5(@Param("sourceConfigId") Long sourceConfigId,
                                         @Param("sourcePathMd5") String sourcePathMd5);

    List<TrackEntity> selectByConfigAndPathMd5In(@Param("sourceConfigId") Long sourceConfigId,
                                                 @Param("sourcePathMd5List") List<String> sourcePathMd5List);

    int softDeleteByTaskId(@Param("taskId") Long taskId, @Param("configId") Long configId);

    int softDeleteByLastScanTaskId(@Param("taskId") Long taskId, @Param("configId") Long configId);

    int touchLastScanTaskByPathMd5In(@Param("taskId") Long taskId,
                                     @Param("configId") Long configId,
                                     @Param("sourcePathMd5List") List<String> sourcePathMd5List);

    int touchLastScanTaskByConfig(@Param("taskId") Long taskId,
                                  @Param("configId") Long configId);

    int touchLastScanTaskByDirectoryPrefix(@Param("taskId") Long taskId,
                                           @Param("configId") Long configId,
                                           @Param("likePattern") String likePattern);

    List<TrackEntity> selectPage(@Param("offset") int offset,
                                 @Param("pageSize") int pageSize,
                                 @Param("keyword") String keyword,
                                 @Param("artist") String artist,
                                 @Param("album") String album,
                                 @Param("genre") String genre,
                                 @Param("year") Integer year,
                                 @Param("sortBy") String sortBy,
                                 @Param("sortOrder") String sortOrder);

    long count(@Param("keyword") String keyword,
               @Param("artist") String artist,
               @Param("album") String album,
               @Param("genre") String genre,
               @Param("year") Integer year);

    TrackEntity selectById(@Param("id") Long id);

    List<TrackEntity> search(@Param("keyword") String keyword, @Param("limit") int limit);

    // --- Feature 2: Duplicate filtering ---

    List<DuplicateGroup> selectDuplicateGroups(@Param("configId") Long configId);

    List<TrackEntity> selectByNormalizedTitleAndArtist(@Param("configId") Long configId,
                                                       @Param("normalizedTitle") String normalizedTitle,
                                                       @Param("normalizedArtist") String normalizedArtist);

    int softDeleteByIds(@Param("ids") List<Long> ids);

    int updateMetadata(TrackEntity entity);

    // --- Feature 8: Batch operations (defined in TrackMapper.xml) ---

    int batchUpsert(@Param("list") List<TrackEntity> list);

    // --- Recommendation queries ---

    /** Most recently added tracks (by created_at DESC). */
    List<TrackEntity> selectRecentlyAdded(@Param("limit") int limit);

    /** Most recently added distinct albums (album + artist) with metadata. */
    List<Map<String, Object>> selectRecentAlbums(@Param("limit") int limit);

    /** Artist info: track count and a representative track ID for cover art. */
    Map<String, Object> selectArtistInfo(@Param("artist") String artist);

    /** Tracks by a specific genre, randomly ordered. */
    List<TrackEntity> selectByGenreRandom(@Param("genre") String genre, @Param("limit") int limit);

    /** Random tracks NOT in the provided ID set (for REDISCOVER shelf). */
    List<TrackEntity> selectRandomExcluding(@Param("excludeIds") List<Long> excludeIds,
                                             @Param("limit") int limit);

    List<TrackEntity> selectSmartTracks(@Param("limit") int limit,
                                        @Param("sortBy") String sortBy,
                                        @Param("sortOrder") String sortOrder,
                                        @Param("genre") String genre,
                                        @Param("artist") String artist);
}
