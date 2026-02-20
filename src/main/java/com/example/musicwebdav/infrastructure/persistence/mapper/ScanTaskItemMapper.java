package com.example.musicwebdav.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskItemMapper {

    int insert(@Param("taskId") Long taskId,
               @Param("sourcePath") String sourcePath,
               @Param("sourcePathMd5") String sourcePathMd5,
               @Param("itemStatus") String itemStatus,
               @Param("errorCode") String errorCode,
               @Param("errorMessage") String errorMessage);
}
