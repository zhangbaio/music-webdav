package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, username, password_hash, role, created_at, updated_at FROM users WHERE username = #{username}")
    UserEntity selectByUsername(@Param("username") String username);

    @Select("SELECT id, username, password_hash, role, created_at, updated_at FROM users WHERE id = #{id}")
    UserEntity selectById(@Param("id") Long id);
}
