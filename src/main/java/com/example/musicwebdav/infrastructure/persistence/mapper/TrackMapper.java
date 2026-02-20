package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.domain.model.DuplicateGroup;
import com.example.musicwebdav.infrastructure.persistence.entity.TrackEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TrackMapper {

    @Insert("INSERT INTO track("
            + "source_config_id, source_path, source_path_md5, source_etag, source_last_modified, source_size, mime_type, content_hash, "
            + "title, artist, album, album_artist, track_no, disc_no, `year`, genre, duration_sec, bitrate, sample_rate, channels, "
            + "has_cover, cover_art_url, has_lyric, lyric_path, is_deleted, last_scan_task_id"
            + ") VALUES ("
            + "#{sourceConfigId}, #{sourcePath}, #{sourcePathMd5}, #{sourceEtag}, #{sourceLastModified}, #{sourceSize}, #{mimeType}, #{contentHash}, "
            + "#{title}, #{artist}, #{album}, #{albumArtist}, #{trackNo}, #{discNo}, #{year}, #{genre}, #{durationSec}, #{bitrate}, #{sampleRate}, #{channels}, "
            + "#{hasCover}, #{coverArtUrl}, #{hasLyric}, #{lyricPath}, 0, #{lastScanTaskId}"
            + ") ON DUPLICATE KEY UPDATE "
            + "source_config_id = VALUES(source_config_id), "
            + "source_path = VALUES(source_path), "
            + "source_etag = VALUES(source_etag), "
            + "source_last_modified = VALUES(source_last_modified), "
            + "source_size = VALUES(source_size), "
            + "mime_type = VALUES(mime_type), "
            + "content_hash = VALUES(content_hash), "
            + "title = VALUES(title), "
            + "artist = VALUES(artist), "
            + "album = VALUES(album), "
            + "album_artist = VALUES(album_artist), "
            + "track_no = VALUES(track_no), "
            + "disc_no = VALUES(disc_no), "
            + "`year` = VALUES(`year`), "
            + "genre = VALUES(genre), "
            + "duration_sec = VALUES(duration_sec), "
            + "bitrate = VALUES(bitrate), "
            + "sample_rate = VALUES(sample_rate), "
            + "channels = VALUES(channels), "
            + "has_cover = VALUES(has_cover), "
            + "cover_art_url = VALUES(cover_art_url), "
            + "has_lyric = VALUES(has_lyric), "
            + "lyric_path = VALUES(lyric_path), "
            + "is_deleted = 0, "
            + "last_scan_task_id = VALUES(last_scan_task_id), "
            + "updated_at = NOW()")
    int upsert(TrackEntity entity);

    @Select("SELECT id, source_config_id, source_path, source_path_md5, source_etag, source_last_modified, source_size, mime_type, content_hash, "
            + "title, artist, album, album_artist, track_no, disc_no, `year`, genre, duration_sec, bitrate, sample_rate, channels, "
            + "has_cover, cover_art_url, has_lyric, lyric_path, is_deleted, last_scan_task_id, created_at, updated_at "
            + "FROM track WHERE source_config_id = #{sourceConfigId} AND source_path_md5 = #{sourcePathMd5}")
    TrackEntity selectByConfigAndPathMd5(@Param("sourceConfigId") Long sourceConfigId,
                                         @Param("sourcePathMd5") String sourcePathMd5);

    @Select("<script>"
            + "SELECT id, source_config_id, source_path, source_path_md5, source_etag, source_last_modified, source_size, mime_type, content_hash, "
            + "title, artist, album, album_artist, track_no, disc_no, `year`, genre, duration_sec, bitrate, sample_rate, channels, "
            + "has_cover, cover_art_url, has_lyric, lyric_path, is_deleted, last_scan_task_id, created_at, updated_at "
            + "FROM track WHERE source_config_id = #{sourceConfigId} "
            + "AND source_path_md5 IN "
            + "<foreach item='md5' collection='sourcePathMd5List' open='(' separator=',' close=')'>"
            + "#{md5}"
            + "</foreach>"
            + "</script>")
    List<TrackEntity> selectByConfigAndPathMd5In(@Param("sourceConfigId") Long sourceConfigId,
                                                 @Param("sourcePathMd5List") List<String> sourcePathMd5List);

    @Update("UPDATE track t LEFT JOIN scan_task_seen_file s "
            + "ON s.task_id = #{taskId} AND s.source_path_md5 = t.source_path_md5 "
            + "SET t.is_deleted = 1, t.updated_at = NOW() "
            + "WHERE s.id IS NULL AND t.is_deleted = 0 AND t.source_config_id = #{configId}")
    int softDeleteByTaskId(@Param("taskId") Long taskId, @Param("configId") Long configId);

    @Update("UPDATE track SET is_deleted = 1, updated_at = NOW() "
            + "WHERE is_deleted = 0 AND source_config_id = #{configId} "
            + "AND (last_scan_task_id IS NULL OR last_scan_task_id <> #{taskId})")
    int softDeleteByLastScanTaskId(@Param("taskId") Long taskId, @Param("configId") Long configId);

    @Update("<script>"
            + "UPDATE track SET last_scan_task_id = #{taskId} "
            + "WHERE is_deleted = 0 AND source_config_id = #{configId} "
            + "AND source_path_md5 IN "
            + "<foreach item='md5' collection='sourcePathMd5List' open='(' separator=',' close=')'>"
            + "#{md5}"
            + "</foreach>"
            + "</script>")
    int touchLastScanTaskByPathMd5In(@Param("taskId") Long taskId,
                                     @Param("configId") Long configId,
                                     @Param("sourcePathMd5List") List<String> sourcePathMd5List);

    @Update("UPDATE track SET last_scan_task_id = #{taskId} "
            + "WHERE is_deleted = 0 AND source_config_id = #{configId}")
    int touchLastScanTaskByConfig(@Param("taskId") Long taskId,
                                  @Param("configId") Long configId);

    @Update("UPDATE track SET last_scan_task_id = #{taskId} "
            + "WHERE is_deleted = 0 AND source_config_id = #{configId} "
            + "AND source_path LIKE #{likePattern} ESCAPE '\\\\'")
    int touchLastScanTaskByDirectoryPrefix(@Param("taskId") Long taskId,
                                           @Param("configId") Long configId,
                                           @Param("likePattern") String likePattern);

    @Select("<script>"
            + "SELECT id, title, artist, album, source_path, duration_sec, has_lyric "
            + "FROM track "
            + "WHERE is_deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR artist LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR album LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + "<if test='artist != null and artist != \"\"'>"
            + " AND artist = #{artist}"
            + "</if>"
            + "<if test='album != null and album != \"\"'>"
            + " AND album = #{album}"
            + "</if>"
            + "<if test='genre != null and genre != \"\"'>"
            + " AND genre = #{genre}"
            + "</if>"
            + "<if test='year != null'>"
            + " AND `year` = #{year}"
            + "</if>"
            + " ORDER BY "
            + "<choose>"
            + "  <when test='sortBy == \"title\"'>title</when>"
            + "  <when test='sortBy == \"artist\"'>artist</when>"
            + "  <when test='sortBy == \"album\"'>album</when>"
            + "  <when test='sortBy == \"year\"'>`year`</when>"
            + "  <when test='sortBy == \"created_at\"'>created_at</when>"
            + "  <otherwise>updated_at</otherwise>"
            + "</choose>"
            + " "
            + "<choose>"
            + "  <when test='sortOrder == \"ASC\"'>ASC</when>"
            + "  <otherwise>DESC</otherwise>"
            + "</choose>"
            + " LIMIT #{offset}, #{pageSize}"
            + "</script>")
    List<TrackEntity> selectPage(@Param("offset") int offset,
                                 @Param("pageSize") int pageSize,
                                 @Param("keyword") String keyword,
                                 @Param("artist") String artist,
                                 @Param("album") String album,
                                 @Param("genre") String genre,
                                 @Param("year") Integer year,
                                 @Param("sortBy") String sortBy,
                                 @Param("sortOrder") String sortOrder);

    @Select("<script>"
            + "SELECT COUNT(1) "
            + "FROM track "
            + "WHERE is_deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'>"
            + " AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR artist LIKE CONCAT('%', #{keyword}, '%') "
            + "   OR album LIKE CONCAT('%', #{keyword}, '%'))"
            + "</if>"
            + "<if test='artist != null and artist != \"\"'>"
            + " AND artist = #{artist}"
            + "</if>"
            + "<if test='album != null and album != \"\"'>"
            + " AND album = #{album}"
            + "</if>"
            + "<if test='genre != null and genre != \"\"'>"
            + " AND genre = #{genre}"
            + "</if>"
            + "<if test='year != null'>"
            + " AND `year` = #{year}"
            + "</if>"
            + "</script>")
    long count(@Param("keyword") String keyword,
               @Param("artist") String artist,
               @Param("album") String album,
               @Param("genre") String genre,
               @Param("year") Integer year);

    @Select("SELECT id, source_config_id, source_path, source_path_md5, source_etag, source_last_modified, source_size, mime_type, content_hash, "
            + "title, artist, album, album_artist, track_no, disc_no, `year`, genre, duration_sec, bitrate, sample_rate, channels, "
            + "has_cover, cover_art_url, has_lyric, lyric_path, is_deleted, last_scan_task_id, created_at, updated_at "
            + "FROM track WHERE id = #{id} AND is_deleted = 0")
    TrackEntity selectById(@Param("id") Long id);

    @Select("SELECT id, title, artist, album, source_path, duration_sec, has_lyric "
            + "FROM track WHERE is_deleted = 0 "
            + "AND (title LIKE CONCAT('%', #{keyword}, '%') "
            + "OR artist LIKE CONCAT('%', #{keyword}, '%') "
            + "OR album LIKE CONCAT('%', #{keyword}, '%')) "
            + "ORDER BY updated_at DESC LIMIT #{limit}")
    List<TrackEntity> search(@Param("keyword") String keyword, @Param("limit") int limit);

    // --- Feature 2: Duplicate filtering ---

    @Select("SELECT LOWER(TRIM(title)) AS normalizedTitle, LOWER(TRIM(artist)) AS normalizedArtist, COUNT(*) AS count "
            + "FROM track WHERE is_deleted = 0 AND source_config_id = #{configId} "
            + "GROUP BY LOWER(TRIM(title)), LOWER(TRIM(artist)) HAVING COUNT(*) > 1")
    List<DuplicateGroup> selectDuplicateGroups(@Param("configId") Long configId);

    @Select("SELECT id, source_config_id, source_path, source_size, title, artist "
            + "FROM track WHERE is_deleted = 0 AND source_config_id = #{configId} "
            + "AND LOWER(TRIM(title)) = #{normalizedTitle} AND LOWER(TRIM(artist)) = #{normalizedArtist}")
    List<TrackEntity> selectByNormalizedTitleAndArtist(@Param("configId") Long configId,
                                                       @Param("normalizedTitle") String normalizedTitle,
                                                       @Param("normalizedArtist") String normalizedArtist);

    @Update("UPDATE track SET is_deleted = 1, updated_at = NOW() WHERE id IN "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>"
            + "#{id}"
            + "</foreach>"
            + "</script>")
    int softDeleteByIds(@Param("ids") List<Long> ids);

    @Update("UPDATE track SET "
            + "title = #{title}, "
            + "artist = #{artist}, "
            + "album = #{album}, "
            + "album_artist = #{albumArtist}, "
            + "track_no = #{trackNo}, "
            + "disc_no = #{discNo}, "
            + "`year` = #{year}, "
            + "genre = #{genre}, "
            + "updated_at = NOW() "
            + "WHERE id = #{id}")
    int updateMetadata(TrackEntity entity);

    // --- Feature 8: Batch operations (defined in TrackMapper.xml) ---

    int batchUpsert(@Param("list") List<TrackEntity> list);

    // --- Recommendation queries ---

    /** Most recently added tracks (by created_at DESC). */
    @Select("SELECT id, title, artist, album, source_path, duration_sec, has_lyric "
            + "FROM track WHERE is_deleted = 0 "
            + "ORDER BY created_at DESC "
            + "LIMIT #{limit}")
    List<TrackEntity> selectRecentlyAdded(@Param("limit") int limit);

    /** Most recently added distinct albums (album + artist) with metadata. */
    @Select("SELECT t.album, t.artist, COUNT(*) AS trackCount, MIN(t.id) AS coverTrackId, MAX(t.`year`) AS `year` "
            + "FROM track t "
            + "WHERE t.is_deleted = 0 AND t.album IS NOT NULL AND t.album <> '' "
            + "GROUP BY t.album, t.artist "
            + "ORDER BY MAX(t.created_at) DESC "
            + "LIMIT #{limit}")
    List<java.util.Map<String, Object>> selectRecentAlbums(@Param("limit") int limit);

    /** Artist info: track count and a representative track ID for cover art. */
    @Select("SELECT COUNT(*) AS trackCount, MIN(id) AS coverTrackId "
            + "FROM track WHERE is_deleted = 0 AND artist = #{artist}")
    java.util.Map<String, Object> selectArtistInfo(@Param("artist") String artist);

    /** Tracks by a specific genre, randomly ordered. */
    @Select("SELECT id, title, artist, album, source_path, duration_sec, has_lyric "
            + "FROM track WHERE is_deleted = 0 AND genre = #{genre} "
            + "ORDER BY RAND() "
            + "LIMIT #{limit}")
    List<TrackEntity> selectByGenreRandom(@Param("genre") String genre, @Param("limit") int limit);

    /** Random tracks NOT in the provided ID set (for REDISCOVER shelf). */
    @Select("<script>"
            + "SELECT id, title, artist, album, source_path, duration_sec, has_lyric "
            + "FROM track WHERE is_deleted = 0 "
            + "<if test='excludeIds != null and excludeIds.size() > 0'>"
            + " AND id NOT IN "
            + "<foreach item='eid' collection='excludeIds' open='(' separator=',' close=')'>"
            + "#{eid}"
            + "</foreach>"
            + "</if>"
            + " ORDER BY RAND() "
            + " LIMIT #{limit}"
            + "</script>")
    List<TrackEntity> selectRandomExcluding(@Param("excludeIds") List<Long> excludeIds,
                                             @Param("limit") int limit);
}
