package com.example.management.auth.exception;

/** 가입 대기 세션 저장소(Redis)를 쓸 수 없는 경우. 대체 경로가 없으므로 서버 오류(500)로 응답한다. */
public class SignupSessionStoreException extends RuntimeException {

    public SignupSessionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}