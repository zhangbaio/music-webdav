package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PlayEventEntity {

    private Long id;

    private Long trackId;

    private String eventType;

    private Integer durationSec;

    private LocalDateTime createdAt;
}
