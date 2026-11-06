package com.example.management.common.api;

/** 명세에서 정의한 모든 성공 API의 공통 응답 래퍼. */
public record ApiResponse<T>(
        int code,
        T data,
        String message
) {
    public static <T> ApiResponse<T> success(int code, T data, String message) {
        return new ApiResponse<>(code, data, message);
    }
}
