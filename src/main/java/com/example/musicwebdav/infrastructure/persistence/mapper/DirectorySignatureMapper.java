package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.DirectorySignatureEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DirectorySignatureMapper {

    @Select("SELECT id, config_id, dir_path, dir_path_md5, dir_etag, dir_last_modified, child_count, "
            + "last_verified_at, created_at, updated_at "
            + "FROM directory_signature WHERE config_id = #{configId} AND dir_path_md5 = #{dirPathMd5}")
    DirectorySignatureEntity selectByConfigAndDirPathMd5(@Param("configId") Long configId,
                                                         @Param("dirPathMd5") String dirPathMd5);

    @Insert("INSERT INTO directory_signature(config_id, dir_path, dir_path_md5, dir_etag, "
            + "dir_last_modified, child_count, last_verified_at) "
            + "VALUES(#{configId}, #{dirPath}, #{dirPathMd5}, #{dirEtag}, "
            + "#{dirLastModified}, #{childCount}, NOW()) "
            + "ON DUPLICATE KEY UPDATE "
            + "dir_etag = VALUES(dir_etag), dir_last_modified = VALUES(dir_last_modified), "
            + "child_count = VALUES(child_count), last_verified_at = NOW(), updated_at = NOW()")
    int upsert(DirectorySignatureEntity entity);
}
