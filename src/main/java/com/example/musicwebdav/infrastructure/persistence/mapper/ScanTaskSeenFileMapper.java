package com.example.musicwebdav.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskSeenFileMapper {

    int insert(@Param("taskId") Long taskId, @Param("sourcePathMd5") String sourcePathMd5);

    /** Batch insert seen files. Defined in ScanTaskSeenFileMapper.xml */
    int batchInsert(@Param("taskId") Long taskId, @Param("md5List") List<String> md5List);

    int deleteByTaskId(@Param("taskId") Long taskId);
}
