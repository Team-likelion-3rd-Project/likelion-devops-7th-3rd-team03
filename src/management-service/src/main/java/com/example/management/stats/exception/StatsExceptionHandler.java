package com.example.management.stats.exception;

import com.example.management.common.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** 통계 API 오류를 공통 형식으로 바꾼다. 팀 없음(404)은 WorkspaceExceptionHandler, 그 밖의 400 은 GlobalExceptionHandler 가 맡는다 */
@RestControllerAdvice(basePackages = "com.example.management.stats")
public class StatsExceptionHandler {

    @ExceptionHandler(StatsLinkNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLinkNotFound(StatsLinkNotFoundException e) {
        ApiErrorResponse body = new ApiErrorResponse(404,
                new ApiErrorResponse.ErrorBody("LINK_NOT_FOUND", e.getMessage(), Map.of("linkIds", e.getLinkIds())));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 필수 파라미터 누락, 날짜 형식 오류 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(Exception e) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(400, "INVALID_REQUEST", "요청 값을 확인해주세요."));
    }
}