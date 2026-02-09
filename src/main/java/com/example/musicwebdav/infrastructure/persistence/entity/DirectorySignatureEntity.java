package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DirectorySignatureEntity {

    private Long id;

    private Long configId;

    private String dirPath;

    private String dirPathMd5;

    private String dirEtag;

    private LocalDateTime dirLastModified;

    private Integer childCount;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
