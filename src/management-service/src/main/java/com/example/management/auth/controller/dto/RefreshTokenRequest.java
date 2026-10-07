package com.example.management.auth.controller.dto;

/** logout / reissue 공통 요청 — 클라이언트가 보관 중인 원본 refresh token */
public record RefreshTokenRequest(String refreshToken) {
}
