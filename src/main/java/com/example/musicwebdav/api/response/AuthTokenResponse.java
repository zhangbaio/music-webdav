package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthTokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresInSeconds;
    private Long refreshExpiresInSeconds;
}
