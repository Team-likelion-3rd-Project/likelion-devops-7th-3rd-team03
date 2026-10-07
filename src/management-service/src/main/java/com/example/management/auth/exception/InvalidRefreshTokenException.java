package com.example.management.auth.exception;

/** refresh token이 존재하지 않거나(로그아웃/재사용됨), 만료된 경우 던진다 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("유효하지 않은 refresh token입니다");
    }
}
