package com.example.musicwebdav.api.response;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackSessionResponse {

    private Long trackId;
    private String signedStreamPath;
    private Long issuedAtEpochSecond;
    private Long expiresAtEpochSecond;
    private Long refreshBeforeExpirySeconds;
    private String streamMode;
    private String directStreamUrl;
    private Map<String, String> directHeaders;
    private String fallbackSignedStreamPath;
}
