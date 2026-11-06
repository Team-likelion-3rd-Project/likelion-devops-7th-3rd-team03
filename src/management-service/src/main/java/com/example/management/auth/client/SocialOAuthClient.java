package com.example.management.auth.client;

import com.example.management.auth.domain.SocialProvider;

/**
 * provider별 OAuth 연동 구현이 따르는 인터페이스.
 * state는 네이버 토큰 교환에만 필요하다 — 다른 provider는 무시한다.
 */
public interface SocialOAuthClient {
    SocialProvider provider();
    SocialProfile authenticate(String code, String state);
}
