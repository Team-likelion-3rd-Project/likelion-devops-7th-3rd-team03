package com.example.management.common.exception;

import com.example.management.common.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationRequired(
            AuthenticationRequiredException e
    ) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
    }

    // JSON 형식이 잘못됐거나 날짜 형식을 읽을 수 없는 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException e
    ) {
        return invalidRequest();
    }

    // DTO의 @NotBlank, @Size, @AssertTrue 등의 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidArgument(
            MethodArgumentNotValidException e
    ) {
        return invalidRequest();
    }

    // 잘못된 인자 처리
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException e
    ) {
        return invalidRequest();
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해주세요.");
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String type,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), type, message));
    }
}