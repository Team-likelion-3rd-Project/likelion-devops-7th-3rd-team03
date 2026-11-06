package com.example.management.auth.controller.dto;

import com.example.management.auth.service.AuthTokens;
import com.example.management.auth.service.LoginResult;
import com.example.management.auth.signup.SignupSession;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 소셜 로그인 응답. 두 형태 중 하나로 나간다(null 필드는 생략).
 * - 로그인 완료: {signupRequired:false, accessToken, refreshToken}
 * - 가입 필요: {signupRequired:true, signupToken, prefill{nickname, email, profileImageUrl}}
 * prefill 안의 값은 소셜 응답에 없으면 null로 그대로 내려간다.
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean signupRequired,
        String accessToken,
        String refreshToken,
        String signupToken,
        Prefill prefill
) {

    public record Prefill(String nickname, String email, String profileImageUrl) {
    }

    public static LoginResponse from(LoginResult result) {
        if (result instanceof LoginResult.Authenticated authenticated) {
            AuthTokens tokens = authenticated.tokens();
            return new LoginResponse(false, tokens.accessToken(), tokens.refreshToken(), null, null);
        }
        if (result instanceof LoginResult.SignupRequired signupRequired) {
            SignupSession session = signupRequired.session();
            Prefill prefill = new Prefill(session.nickname(), session.email(), session.profileImageUrl());
            return new LoginResponse(true, null, null, signupRequired.signupToken(), prefill);
        }
        throw new IllegalStateException("처리하지 않은 로그인 결과입니다: " + result);
    }
}