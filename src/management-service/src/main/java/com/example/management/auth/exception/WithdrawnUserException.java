package com.example.management.auth.exception;

/** 탈퇴(WITHDRAWN) 상태인 사용자가 로그인/재발급을 시도할 때 던진다 */
public class WithdrawnUserException extends RuntimeException {

    public WithdrawnUserException(String userId) {
        super("탈퇴한 사용자입니다: " + userId);
    }
}
