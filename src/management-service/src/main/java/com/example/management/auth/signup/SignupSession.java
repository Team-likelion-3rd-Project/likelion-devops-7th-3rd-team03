package com.example.management.auth.signup;

import com.example.management.auth.domain.SocialProvider;

/**
 * 가입 대기 세션에 저장하는 값. 미가입 소셜 사용자가 추가정보를 입력하는 동안 Redis에 보관한다.
 * nickname·email·profileImageUrl은 추가정보 폼을 미리 채우는 값(prefill)이며, 소셜 응답에 없으면 null이다.
 */
public record SignupSession(
        SocialProvider provider,
        String socialId,
        String nickname,
        String email,
        String profileImageUrl
) {
}