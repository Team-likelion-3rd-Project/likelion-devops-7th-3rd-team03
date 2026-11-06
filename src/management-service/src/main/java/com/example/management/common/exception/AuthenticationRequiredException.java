package com.example.management.common.exception;

/** 유효한 인증 주체를 현재 요청에서 확인할 수 없을 때 사용한다. */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException() {
        super("인증이 필요합니다.");
    }
}
