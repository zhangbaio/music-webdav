package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.ScanTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScanTaskMapper {

    @Insert("INSERT INTO scan_task(task_type, status, config_id, total_files, audio_files, added_count, "
            + "updated_count, deleted_count, failed_count, processed_directories, total_directories, progress_pct) "
            + "VALUES(#{taskType}, #{status}, #{configId}, #{totalFiles}, #{audioFiles}, #{addedCount}, "
            + "#{updatedCount}, #{deletedCount}, #{failedCount}, #{processedDirectories}, #{totalDirectories}, #{progressPct})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanTaskEntity entity);

    @Select("SELECT id, task_type, status, config_id, start_time, end_time, total_files, audio_files, "
            + "added_count, updated_count, deleted_count, failed_count, "
            + "processed_directories, total_directories, last_synced_dir, progress_pct, "
            + "error_summary, created_at, updated_at "
            + "FROM scan_task WHERE id = #{id}")
    ScanTaskEntity selectById(@Param("id") Long id);

    @Select("SELECT status FROM scan_task WHERE id = #{id}")
    String selectStatusById(@Param("id") Long id);

    @Update("UPDATE scan_task SET status = #{status}, start_time = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markRunning(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE scan_task SET status = #{status}, end_time = NOW(), total_files = #{totalFiles}, "
            + "audio_files = #{audioFiles}, added_count = #{addedCount}, updated_count = #{updatedCount}, "
            + "deleted_count = #{deletedCount}, failed_count = #{failedCount}, error_summary = #{errorSummary}, "
            + "updated_at = NOW() WHERE id = #{id} AND status = 'RUNNING'")
    int markFinished(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("totalFiles") int totalFiles,
                     @Param("audioFiles") int audioFiles,
                     @Param("addedCount") int addedCount,
                     @Param("updatedCount") int updatedCount,
                     @Param("deletedCount") int deletedCount,
                     @Param("failedCount") int failedCount,
                     @Param("errorSummary") String errorSummary);

    @Update("UPDATE scan_task SET status = #{status}, end_time = NOW(), failed_count = #{failedCount}, "
            + "error_summary = #{errorSummary}, updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markFailedBeforeRunning(@Param("id") Long id,
                                @Param("status") String status,
                                @Param("failedCount") int failedCount,
                                @Param("errorSummary") String errorSummary);

    @Update("UPDATE scan_task SET status = #{status}, end_time = NOW(), updated_at = NOW() "
            + "WHERE id = #{id} AND status IN ('PENDING','RUNNING')")
    int cancel(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE scan_task SET total_files = #{totalFiles}, audio_files = #{audioFiles}, "
            + "added_count = #{addedCount}, updated_count = #{updatedCount}, deleted_count = #{deletedCount}, "
            + "failed_count = #{failedCount}, error_summary = #{errorSummary}, updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'CANCELED'")
    int updateCanceledStats(@Param("id") Long id,
                            @Param("totalFiles") int totalFiles,
                            @Param("audioFiles") int audioFiles,
                            @Param("addedCount") int addedCount,
                            @Param("updatedCount") int updatedCount,
                            @Param("deletedCount") int deletedCount,
                            @Param("failedCount") int failedCount,
                            @Param("errorSummary") String errorSummary);

    @Update("UPDATE scan_task SET "
            + "total_files = #{totalFiles}, audio_files = #{audioFiles}, "
            + "added_count = #{addedCount}, updated_count = #{updatedCount}, "
            + "failed_count = #{failedCount}, "
            + "processed_directories = #{processedDirectories}, "
            + "total_directories = #{totalDirectories}, "
            + "last_synced_dir = #{lastSyncedDir}, "
            + "progress_pct = #{progressPct}, "
            + "updated_at = NOW() "
            + "WHERE id = #{id} AND status = 'RUNNING'")
    int updateProgress(@Param("id") Long id,
                       @Param("totalFiles") int totalFiles,
                       @Param("audioFiles") int audioFiles,
                       @Param("addedCount") int addedCount,
                       @Param("updatedCount") int updatedCount,
                       @Param("failedCount") int failedCount,
                       @Param("processedDirectories") int processedDirectories,
                       @Param("totalDirectories") int totalDirectories,
                       @Param("lastSyncedDir") String lastSyncedDir,
                       @Param("progressPct") int progressPct);
}
