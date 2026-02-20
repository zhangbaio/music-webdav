package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PlaylistEntity {

    private Long id;

    private String name;

    private String playlistType;

    private String systemCode;

    private Integer sortNo;

    private Integer isDeleted;

    private Integer trackCount;

    private String rules;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
