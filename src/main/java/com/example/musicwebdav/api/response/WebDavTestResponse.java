package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebDavTestResponse {

    private String status;
    private String directoryAccess;
    private long latencyMs;
    private String code;
    private String message;
    private String userAction;
}
