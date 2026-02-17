package com.example.musicwebdav.api.response;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoverSessionResponse {

    private Long trackId;
    private String coverMode;
    private String directCoverUrl;
    private Map<String, String> directHeaders;
    private String fallbackCoverPath;
}
