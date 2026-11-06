package com.example.management.auth.service;

/** 발급한 토큰 쌍. refreshToken은 원본 값(해시 전)이며 응답에 한 번만 노출된다 */
public record AuthTokens(String accessToken, String refreshToken) {
}