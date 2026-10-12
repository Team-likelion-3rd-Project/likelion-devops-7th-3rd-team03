package com.example.management.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GET https://openidconnect.googleapis.com/v1/userinfo 응답.
 * 카카오와 달리 평평한 구조라 nested null-safe 접근자가 필요 없다 —
 * email/name/picture는 스코프에 동의하지 않으면 그냥 필드 자체가 없어서 null로 들어온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleUserInfoResponse(
        String sub,
        String email,
        String name,
        String picture
) {
}