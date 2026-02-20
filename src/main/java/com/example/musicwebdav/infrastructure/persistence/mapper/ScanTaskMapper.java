package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.ScanTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskMapper {

    int insert(ScanTaskEntity entity);

    java.util.List<ScanTaskEntity> selectInterruptedTasks();

    ScanTaskEntity selectById(@Param("id") Long id);

    String selectStatusById(@Param("id") Long id);

    int countActiveByConfigId(@Param("configId") Long configId);

    int markRunning(@Param("id") Long id, @Param("status") String status);

    int markFinished(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("totalFiles") int totalFiles,
                     @Param("audioFiles") int audioFiles,
                     @Param("addedCount") int addedCount,
                     @Param("updatedCount") int updatedCount,
                     @Param("deletedCount") int deletedCount,
                     @Param("failedCount") int failedCount,
                     @Param("errorSummary") String errorSummary);

    int markFailedBeforeRunning(@Param("id") Long id,
                                @Param("status") String status,
                                @Param("failedCount") int failedCount,
                                @Param("errorSummary") String errorSummary);

    int cancel(@Param("id") Long id, @Param("status") String status);

    int updateCanceledStats(@Param("id") Long id,
                            @Param("totalFiles") int totalFiles,
                            @Param("audioFiles") int audioFiles,
                            @Param("addedCount") int addedCount,
                            @Param("updatedCount") int updatedCount,
                            @Param("deletedCount") int deletedCount,
                            @Param("failedCount") int failedCount,
                            @Param("errorSummary") String errorSummary);

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
