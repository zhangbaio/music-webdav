package com.example.musicwebdav.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebDavRecoveryStatusResponse {

    private String status;

    private Long configId;

    private String code;

    private String message;

    private String userAction;
}
