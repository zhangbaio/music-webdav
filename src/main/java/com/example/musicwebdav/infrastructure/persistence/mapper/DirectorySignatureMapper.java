package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.DirectorySignatureEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DirectorySignatureMapper {

    DirectorySignatureEntity selectByConfigAndDirPathMd5(@Param("configId") Long configId,
                                                         @Param("dirPathMd5") String dirPathMd5);

    int upsert(DirectorySignatureEntity entity);
}
