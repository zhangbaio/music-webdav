package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtistSearchResponse {

    private String artist;
    private Long trackCount;
    private Long coverTrackId;
}
