package com.example.musicwebdav.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskSeenFileMapper {

    @Insert("INSERT IGNORE INTO scan_task_seen_file(task_id, source_path_md5) VALUES(#{taskId}, #{sourcePathMd5})")
    int insert(@Param("taskId") Long taskId, @Param("sourcePathMd5") String sourcePathMd5);

    /** Batch insert seen files. Defined in ScanTaskSeenFileMapper.xml */
    int batchInsert(@Param("taskId") Long taskId, @Param("md5List") List<String> md5List);

    @Delete("DELETE FROM scan_task_seen_file WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);
}
