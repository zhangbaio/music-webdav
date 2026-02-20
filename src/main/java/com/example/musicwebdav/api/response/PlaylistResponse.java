package com.example.musicwebdav.api.response;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaylistResponse {

    private Long id;
    private String name;
    private String playlistType;
    private String systemCode;
    private Integer sortNo;
    private Integer trackCount;
    private String rules;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
