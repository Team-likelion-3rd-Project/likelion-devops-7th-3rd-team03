package com.example.management.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 구글 OAuth 클라이언트 설정. 비활성(미설정) 상태에서는 전부 빈 문자열이 바인딩된다. */
@ConfigurationProperties(prefix = "google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {
}