package com.example.management.auth.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** auth 도메인 예외를 HTTP 응답으로 변환한다. AuthController에서 발생하는 예외만 다룬다 */
@Slf4j
@RestControllerAdvice(basePackages = "com.example.management.auth")
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(WithdrawnUserException.class)
    public ResponseEntity<Map<String, String>> handleWithdrawnUser(WithdrawnUserException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    /**
     * 카카오 API(토큰 교환/사용자 조회) 호출이 실패하면 RestClient가 unchecked RestClientException을
     * 던진다 — 이미 사용됐거나 만료된 인가코드(invalid_grant)가 가장 흔한 원인이다.
     * 원인 상세는 클라이언트에 노출하지 않고 서버 로그에만 남긴다.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> handleKakaoApiFailure(RestClientException e) {
        log.warn("카카오 API 호출 실패", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "카카오 로그인 처리에 실패했습니다. 다시 시도해주세요."));
    }
}
