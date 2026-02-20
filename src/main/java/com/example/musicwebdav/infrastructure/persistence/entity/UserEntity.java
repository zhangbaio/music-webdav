package com.example.musicwebdav.infrastructure.persistence.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserEntity {
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
