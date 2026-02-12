package com.example.musicwebdav.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class WebDavConnectResult {

    private boolean success;

    private String status;

    private String directoryAccess;

    private String code;

    private String message;

    private String userAction;

    public WebDavConnectResult(boolean success,
                               String status,
                               String directoryAccess,
                               String code,
                               String message,
                               String userAction) {
        this.success = success;
        this.status = status;
        this.directoryAccess = directoryAccess;
        this.code = code;
        this.message = message;
        this.userAction = userAction;
    }

    public static WebDavConnectResult success(String message) {
        return new WebDavConnectResult(
                true,
                "SUCCESS",
                "ACCESSIBLE",
                "WEBDAV_TEST_OK",
                message,
                null);
    }

    public static WebDavConnectResult failure(String code,
                                              String message,
                                              String userAction,
                                              String directoryAccess) {
        return new WebDavConnectResult(
                false,
                "FAILED",
                directoryAccess,
                code,
                message,
                userAction);
    }
}
