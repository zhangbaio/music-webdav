package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuthRefreshRequest {

    @NotBlank
    private String refreshToken;
}
