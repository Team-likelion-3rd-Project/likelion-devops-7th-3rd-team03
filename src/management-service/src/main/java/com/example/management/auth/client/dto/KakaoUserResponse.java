package com.example.management.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET https://kapi.kakao.com/v2/user/me 응답.
 * nickname/profileImageUrl/email은 카카오 "선택 동의항목"이라 사용자가 동의하지 않으면
 * kakaoAccount 또는 profile 자체가 null일 수 있다 — 접근자에서 null-safe하게 풀어준다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(
            String email,
            Profile profile
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Profile(
                String nickname,
                @JsonProperty("profile_image_url") String profileImageUrl
        ) {
        }
    }

    public String nickname() {
        return kakaoAccount == null || kakaoAccount.profile() == null ? null : kakaoAccount.profile().nickname();
    }

    public String profileImageUrl() {
        return kakaoAccount == null || kakaoAccount.profile() == null ? null : kakaoAccount.profile().profileImageUrl();
    }

    public String email() {
        return kakaoAccount == null ? null : kakaoAccount.email();
    }
}
