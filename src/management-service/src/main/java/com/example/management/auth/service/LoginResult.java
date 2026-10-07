package com.example.management.auth.service;

/** 로그인/재발급 결과. refreshToken은 원본 값(해시 전)이며 응답에 한 번만 노출된다 */
public record LoginResult(String accessToken, String refreshToken, boolean isNewUser) {
}
