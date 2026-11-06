package com.example.management.links.exception;

/** expiresAt 누락·형식·범위 규칙을 위반했을 때 사용한다. */
public class InvalidExpirationException extends RuntimeException {

    public InvalidExpirationException() {
        super("만료일은 현재보다 미래이며 허용 기간 이내여야 합니다.");
    }
}
