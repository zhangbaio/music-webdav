package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PlaylistTrackEntity {

    private Long id;

    private Long playlistId;

    private Long trackId;

    private Integer orderNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
