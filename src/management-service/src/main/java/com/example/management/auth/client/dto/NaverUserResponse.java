package com.example.management.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET https://openapi.naver.com/v1/nid/me 응답. 실제 프로필은 response 안에 감싸져 온다.
 * nickname/name/email/profile_image는 네이버 "제공 정보" 동의 항목이라 동의하지 않았거나
 * 앱에서 받지 않도록 설정하면 필드 자체가 없다 — 접근자에서 null-safe하게 풀어준다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverUserResponse(
        String resultcode,
        String message,
        Profile response
) {
    /** 조회에 성공했을 때의 resultcode */
    public static final String SUCCESS_CODE = "00";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            String id,
            String nickname,
            String name,
            String email,
            @JsonProperty("profile_image") String profileImage
    ) {
    }

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(resultcode) && response != null && response.id() != null;
    }

    public String id() {
        return response == null ? null : response.id();
    }

    public String nickname() {
        return response == null ? null : response.nickname();
    }

    public String name() {
        return response == null ? null : response.name();
    }

    public String email() {
        return response == null ? null : response.email();
    }

    public String profileImage() {
        return response == null ? null : response.profileImage();
    }
}