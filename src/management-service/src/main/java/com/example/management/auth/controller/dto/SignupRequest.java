package com.example.management.auth.controller.dto;

import com.example.management.auth.signup.ProfileImageUrlPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 가입 완료 요청. 검증은 컨트롤러(@Valid)에서 가입 세션을 소비하기 전에 끝난다.
 * 잘못된 입력으로 세션이 소모되지 않아 사용자가 고쳐서 다시 제출할 수 있다.
 * 닉네임은 중복을 허용하고, 이메일은 본인 확인을 하지 않는 연락용 값이다.
 */
public record SignupRequest(
        @NotBlank String signupToken,
        @NotBlank @Size(max = 50) String nickname,
        @NotBlank @Email @Size(max = 100) String email,
        String profileImageUrl
) {
    /** 프로필 이미지는 선택. 비어 있으면 통과하고, 값이 있으면 https://·500자 이하만 허용한다 */
    @AssertTrue(message = "profileImageUrl은 https://로 시작하고 500자 이하여야 합니다.")
    public boolean isProfileImageUrlValid() {
        return profileImageUrl == null || profileImageUrl.isEmpty() || ProfileImageUrlPolicy.isAllowed(profileImageUrl);
    }
}