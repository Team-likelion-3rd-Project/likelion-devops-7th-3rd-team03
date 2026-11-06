package com.example.management.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT access token 서명 설정 및 access/refresh token 유효기간.
 * refresh token 자체는 JWT가 아닌 랜덤 문자열이지만(해시로 DB 저장), 유효기간 값은
 * 발급 시점에 만료일시(expiresAt)를 계산하는 데 쓰이므로 여기서 함께 관리한다.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessTokenValiditySeconds,
        long refreshTokenValiditySeconds
) {
}
