package com.example.management.auth.exception;

/** state가 필요한 provider(네이버)로 로그인하면서 state를 보내지 않은 경우 */
public class MissingOAuthStateException extends RuntimeException {

    public MissingOAuthStateException(String provider) {
        super("로그인 요청에 state가 필요합니다: " + provider);
    }
}