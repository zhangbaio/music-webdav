package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WebDavConfigEntity {

    private Long id;

    private String name;

    private String baseUrl;

    private String username;

    private String passwordEnc;

    private String rootPath;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
