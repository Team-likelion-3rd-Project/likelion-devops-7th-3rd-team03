package com.example.management.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 REST API 클라이언트 설정.
 * clientSecret은 카카오 개발자 콘솔에서 "활성화"하지 않으면 사용하지 않으므로 nullable로 둔다.
 */
@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {
}
