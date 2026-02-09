package com.example.musicwebdav.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskItemMapper {

    @Insert("INSERT INTO scan_task_item(task_id, source_path, source_path_md5, item_status, error_code, error_message) "
            + "VALUES(#{taskId}, #{sourcePath}, #{sourcePathMd5}, #{itemStatus}, #{errorCode}, #{errorMessage})")
    int insert(@Param("taskId") Long taskId,
               @Param("sourcePath") String sourcePath,
               @Param("sourcePathMd5") String sourcePathMd5,
               @Param("itemStatus") String itemStatus,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage);
}
