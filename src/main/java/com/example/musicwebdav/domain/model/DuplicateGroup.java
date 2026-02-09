package com.example.musicwebdav.domain.model;

import lombok.Data;

@Data
public class DuplicateGroup {

    private String normalizedTitle;

    private String normalizedArtist;

    private int count;
}
