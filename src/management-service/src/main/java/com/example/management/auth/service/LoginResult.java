package com.example.management.auth.service;

import com.example.management.auth.signup.SignupSession;

/**
 * 소셜 로그인 결과. 가입된 회원이면 토큰을 발급하고(Authenticated), 미가입이면 회원을 만들지 않고
 * 가입 대기 세션 토큰과 미리 채울 값을 돌려준다(SignupRequired).
 */
public sealed interface LoginResult {

    record Authenticated(AuthTokens tokens) implements LoginResult {
    }

    record SignupRequired(String signupToken, SignupSession session) implements LoginResult {
    }
}