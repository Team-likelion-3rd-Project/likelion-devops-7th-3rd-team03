package com.example.management.auth.exception;

/** 레지스트리에 등록된 SocialOAuthClient가 없는 provider로 요청한 경우 */
public class UnsupportedProviderException extends RuntimeException {

    public UnsupportedProviderException(String provider) {
        super("지원하지 않는 로그인 방식입니다: " + provider);
    }
}