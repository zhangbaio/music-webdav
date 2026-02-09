package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.ScanCheckpointEntity;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScanCheckpointMapper {

    @Insert("INSERT INTO scan_checkpoint(task_id, dir_path, dir_path_md5, status, "
            + "file_count, processed_count, failed_count, error_message) "
            + "VALUES(#{taskId}, #{dirPath}, #{dirPathMd5}, #{status}, "
            + "#{fileCount}, #{processedCount}, #{failedCount}, #{errorMessage}) "
            + "ON DUPLICATE KEY UPDATE status = VALUES(status), "
            + "file_count = VALUES(file_count), processed_count = VALUES(processed_count), "
            + "failed_count = VALUES(failed_count), error_message = VALUES(error_message)")
    int upsert(ScanCheckpointEntity entity);

    @Select("SELECT dir_path_md5 FROM scan_checkpoint WHERE task_id = #{taskId} AND status = 'COMPLETED'")
    Set<String> selectCompletedDirMd5s(@Param("taskId") Long taskId);

    @Select("SELECT dir_path, dir_path_md5 FROM scan_checkpoint WHERE task_id = #{taskId} AND status = 'FAILED'")
    List<ScanCheckpointEntity> selectFailedDirs(@Param("taskId") Long taskId);

    @Delete("DELETE FROM scan_checkpoint WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
