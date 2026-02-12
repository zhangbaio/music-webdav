package com.example.musicwebdav.api.request;

import lombok.Data;

@Data
public class AdminWebDavRecoveryRequest {

    private Long configId;

    private String baseUrl;

    private String username;

    private String password;

    private String rootPath;

    private Boolean enabled;
}
