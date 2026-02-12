package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuthLoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
