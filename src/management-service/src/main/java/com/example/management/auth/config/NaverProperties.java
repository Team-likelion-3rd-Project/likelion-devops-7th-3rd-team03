package com.example.management.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 OAuth 클라이언트 설정. 비활성(미설정) 상태에서는 전부 빈 문자열이 바인딩된다.
 * 네이버 토큰 요청에는 redirect_uri 대신 state가 들어가므로 redirect URI는 백엔드에 두지 않는다.
 */
@ConfigurationProperties(prefix = "naver")
public record NaverProperties(
        String clientId,
        String clientSecret
) {
}