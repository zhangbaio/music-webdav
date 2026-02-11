package com.example.musicwebdav.infrastructure.persistence.model;

import lombok.Data;

@Data
public class SearchAlbumRow {

    private String album;
    private String artist;
    private Long trackCount;
    private Long coverTrackId;
}
