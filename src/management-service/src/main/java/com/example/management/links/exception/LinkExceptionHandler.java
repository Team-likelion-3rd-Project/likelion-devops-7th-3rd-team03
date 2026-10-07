package com.example.management.links.exception;

import com.example.management.common.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 링크 도메인 예외를 공통 오류 계약으로 변환한다. */
@RestControllerAdvice(basePackages = "com.example.management.links")
public class LinkExceptionHandler {

    @ExceptionHandler(InvalidExpirationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidExpiration(InvalidExpirationException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_EXPIRATION", e.getMessage());
    }

    @ExceptionHandler(LinkLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleLinkLimitExceeded(LinkLimitExceededException e) {
        return error(HttpStatus.BAD_REQUEST, "LINK_LIMIT_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLinkNotFound(LinkNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(NotLinkOwnerException.class)
    public ResponseEntity<ApiErrorResponse> handleNotLinkOwner(NotLinkOwnerException e) {
        return error(HttpStatus.FORBIDDEN, "LINK_ACCESS_DENIED", e.getMessage());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String type, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(status.value(), type, message));
    }
}
