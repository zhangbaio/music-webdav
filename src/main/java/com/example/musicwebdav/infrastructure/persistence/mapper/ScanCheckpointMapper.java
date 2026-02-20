package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.ScanCheckpointEntity;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanCheckpointMapper {

    int upsert(ScanCheckpointEntity entity);

    Set<String> selectCompletedDirMd5s(@Param("taskId") Long taskId);

    List<ScanCheckpointEntity> selectFailedDirs(@Param("taskId") Long taskId);

    int deleteByTaskId(@Param("taskId") Long taskId);
}
