package com.example.musicwebdav.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskSeenFileMapper {

    @Insert("INSERT IGNORE INTO scan_task_seen_file(task_id, source_path_md5) VALUES(#{taskId}, #{sourcePathMd5})")
    int insert(@Param("taskId") Long taskId, @Param("sourcePathMd5") String sourcePathMd5);
}
