package com.example.musicwebdav.api.request;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateWebDavConfigRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    /** Optional root path. Empty value means '/' on backend side. */
    private String rootPath;

    private Boolean enabled = true;
}
