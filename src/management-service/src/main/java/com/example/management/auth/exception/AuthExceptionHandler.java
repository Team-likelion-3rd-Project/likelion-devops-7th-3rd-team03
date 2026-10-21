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

    @ExceptionHandler(UnsupportedProviderException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedProvider(UnsupportedProviderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MissingOAuthStateException.class)
    public ResponseEntity<Map<String, String>> handleMissingOAuthState(MissingOAuthStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }

    /**
     * 가입 토큰 만료·재사용. 입력 오류(400)와 구분해 FE가 안내 후 로그인 화면으로 보낼 수 있게 410으로 응답한다.
     * 401은 FE가 "인증 만료"로 처리하므로 쓰지 않는다.
     */
    @ExceptionHandler(SignupSessionExpiredException.class)
    public ResponseEntity<Map<String, String>> handleSignupSessionExpired(SignupSessionExpiredException e) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", e.getMessage()));
    }

    /**
     * 가입 대기 세션 저장소(Redis) 장애. 처리하지 않으면 /error로 포워드되는데, /error는 permitAll이 아니라서
     * 클라이언트가 서버 오류 대신 401을 받게 된다. 그래서 여기서 명시적으로 500으로 응답한다.
     */
    @ExceptionHandler(SignupSessionStoreException.class)
    public ResponseEntity<Map<String, String>> handleSignupSessionStoreFailure(SignupSessionStoreException e) {
        log.error("가입 대기 세션 저장소 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "일시적인 오류로 가입을 진행할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    /**
     * 소셜 provider API(토큰 교환/사용자 조회) 호출이 실패하면 RestClient가 unchecked
     * RestClientException을 던진다 — 이미 사용됐거나 만료된 인가코드(invalid_grant)가
     * 가장 흔한 원인이다. 원인 상세는 클라이언트에 노출하지 않고 서버 로그에만 남긴다.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> handleSocialApiFailure(RestClientException e) {
        log.warn("소셜 로그인 API 호출 실패", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "소셜 로그인 처리에 실패했습니다. 다시 시도해주세요."));
    }
}
