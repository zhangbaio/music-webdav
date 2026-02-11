package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WebDavConfigMapper {

    @Insert("INSERT INTO webdav_config(name, base_url, username, password_enc, root_path, enabled) "
            + "VALUES(#{name}, #{baseUrl}, #{username}, #{passwordEnc}, #{rootPath}, #{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(WebDavConfigEntity entity);

    @Select("SELECT id, name, base_url, username, password_enc, root_path, enabled, created_at, updated_at "
            + "FROM webdav_config WHERE id = #{id}")
    WebDavConfigEntity selectById(@Param("id") Long id);

    @Select("SELECT id, name, base_url, username, password_enc, root_path, enabled, created_at, updated_at "
            + "FROM webdav_config ORDER BY id DESC")
    List<WebDavConfigEntity> selectAll();

    @Select("SELECT id, name, base_url, username, password_enc, root_path, enabled, created_at, updated_at "
            + "FROM webdav_config WHERE enabled = 1 ORDER BY id ASC")
    List<WebDavConfigEntity> selectEnabled();
}
