package com.example.musicwebdav.api.response;

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
}
