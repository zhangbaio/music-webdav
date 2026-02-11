package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlbumSearchResponse {

    private String album;
    private String artist;
    private Long trackCount;
    private Long coverTrackId;
}
