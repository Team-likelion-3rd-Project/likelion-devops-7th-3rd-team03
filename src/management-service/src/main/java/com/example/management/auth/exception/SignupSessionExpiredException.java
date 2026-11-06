package com.example.management.auth.exception;

/** 가입 토큰이 만료됐거나 이미 사용됐거나 존재하지 않는다(셋은 구분할 수 없다) */
public class SignupSessionExpiredException extends RuntimeException {

    public SignupSessionExpiredException() {
        super("가입 시간이 만료되었습니다. 다시 로그인해주세요.");
    }
}