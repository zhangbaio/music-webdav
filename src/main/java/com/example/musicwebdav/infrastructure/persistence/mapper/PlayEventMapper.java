package com.example.musicwebdav.infrastructure.persistence.mapper;

import com.example.musicwebdav.infrastructure.persistence.entity.PlayEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface PlayEventMapper {

    @Insert("INSERT INTO play_event (track_id, event_type, duration_sec) "
            + "VALUES (#{trackId}, #{eventType}, #{durationSec})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(PlayEventEntity entity);
}
