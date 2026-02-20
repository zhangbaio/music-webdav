package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.WebDavConfigEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebDavConfigMapper {

    int insert(WebDavConfigEntity entity);

    int updateById(WebDavConfigEntity entity);

    WebDavConfigEntity selectById(@Param("id") Long id);

    List<WebDavConfigEntity> selectAll();

    List<WebDavConfigEntity> selectEnabled();

    WebDavConfigEntity selectFirstEnabled();
}
