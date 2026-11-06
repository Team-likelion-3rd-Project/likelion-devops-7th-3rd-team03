package com.example.management.auth.controller.dto;

import com.example.management.auth.service.AuthTokens;

/** 토큰 재발급 응답 */
public record TokenResponse(String accessToken, String refreshToken) {

    public static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }
}