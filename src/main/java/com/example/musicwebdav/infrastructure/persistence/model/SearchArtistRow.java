package com.example.musicwebdav.infrastructure.persistence.model;

import lombok.Data;

@Data
public class SearchArtistRow {

    private String artist;
    private Long trackCount;
    private Long coverTrackId;
}
