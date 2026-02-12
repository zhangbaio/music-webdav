package com.example.musicwebdav.api.request;

import lombok.Data;

@Data
public class AuthLogoutRequest {

    private String refreshToken;
}
