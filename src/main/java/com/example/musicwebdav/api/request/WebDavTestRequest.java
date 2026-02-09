package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WebDavTestRequest {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    @NotBlank
    private String rootPath;
}
