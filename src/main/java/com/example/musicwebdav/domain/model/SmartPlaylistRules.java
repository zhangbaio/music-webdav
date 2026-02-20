package com.example.musicwebdav.domain.model;

import lombok.Data;

@Data
public class SmartPlaylistRules {
    /** Max tracks to return. */
    private Integer limit;
    
    /** Column to sort by: created_at, last_played_at, title, etc. */
    private String sortBy;
    
    /** Sort direction: ASC, DESC. */
    private String sortOrder;
    
    /** Filter by genre if not null. */
    private String genre;
    
    /** Filter by artist if not null. */
    private String artist;
}
