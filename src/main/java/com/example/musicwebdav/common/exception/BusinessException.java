package com.example.musicwebdav.common.exception;

public class BusinessException extends RuntimeException {

    private final String code;
    private final String userAction;

    public BusinessException(String code, String message) {
        this(code, message, null);
    }

    public BusinessException(String code, String message, String userAction) {
        super(message);
        this.code = code;
        this.userAction = userAction;
    }

    public String getCode() {
        return code;
    }

    public String getUserAction() {
        return userAction;
    }
}
