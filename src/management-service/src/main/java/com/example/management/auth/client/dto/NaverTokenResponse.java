package com.example.management.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST https://nid.naver.com/oauth2.0/token 응답 (사용하지 않는 필드는 생략).
 * 네이버는 발급에 실패해도 HTTP 200으로 error/error_description만 담아 보낸다 — 그때 accessToken은 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Integer expiresIn,
        String error,
        @JsonProperty("error_description") String errorDescription
) {
}