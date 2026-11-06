package com.example.management.auth.client;

import com.example.management.auth.client.dto.GoogleUserInfoResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthClientTest {

    @Test
    @DisplayName("toProfile: 모든 필드가 있으면 그대로 SocialProfile에 매핑된다")
    void toProfile_mapsAllFields() {
        GoogleUserInfoResponse user = new GoogleUserInfoResponse("google-sub-1", "user@test.com", "닉네임", "https://image");

        SocialProfile profile = GoogleOAuthClient.toProfile(user);

        assertThat(profile.socialId()).isEqualTo("google-sub-1");
        assertThat(profile.nickname()).isEqualTo("닉네임");
        assertThat(profile.profileImageUrl()).isEqualTo("https://image");
        assertThat(profile.email()).isEqualTo("user@test.com");
    }

    @Test
    @DisplayName("toProfile: 이메일·프로필 이미지가 없으면 예외 없이 null로 매핑된다")
    void toProfile_handlesMissingEmailAndPicture() {
        GoogleUserInfoResponse user = new GoogleUserInfoResponse("google-sub-2", null, "닉네임", null);

        SocialProfile profile = GoogleOAuthClient.toProfile(user);

        assertThat(profile.socialId()).isEqualTo("google-sub-2");
        assertThat(profile.email()).isNull();
        assertThat(profile.profileImageUrl()).isNull();
    }
}