package com.example.management.common.api;

import java.util.Map;

/** 명세에서 정의한 모든 오류 API의 공통 응답 래퍼. */
public record ApiErrorResponse(
        int code,
        ErrorBody error
) {
    public static ApiErrorResponse of(int code, String type, String message) {
        return new ApiErrorResponse(code, new ErrorBody(type, message, Map.of()));
    }

    public record ErrorBody(
            String type,
            String message,
            Map<String, Object> details
    ) {
    }
}
