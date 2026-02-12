package com.example.musicwebdav.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private String code;
    private String message;
    private T data;
    private String userAction;
    private String traceId;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "OK", data, null, currentTraceId());
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null, null, currentTraceId());
    }

    public static <T> ApiResponse<T> fail(String code, String message, String userAction) {
        return new ApiResponse<>(code, message, null, userAction, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get("requestId");
    }
}
