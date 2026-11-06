package com.example.management.auth.signup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileImageUrlPolicyTest {

    @Test
    @DisplayName("https://로 시작하고 500자 이하면 허용한다")
    void allowsHttpsUrlWithinLength() {
        String url = "https://example.com/" + "a".repeat(ProfileImageUrlPolicy.MAX_LENGTH - "https://example.com/".length());

        assertThat(url).hasSize(ProfileImageUrlPolicy.MAX_LENGTH);
        assertThat(ProfileImageUrlPolicy.allowedOrNull(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("500자를 넘으면 허용하지 않는다")
    void rejectsTooLongUrl() {
        String url = "https://example.com/" + "a".repeat(ProfileImageUrlPolicy.MAX_LENGTH);

        assertThat(ProfileImageUrlPolicy.isAllowed(url)).isFalse();
        assertThat(ProfileImageUrlPolicy.allowedOrNull(url)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"http://example.com/p.png", "javascript:alert(1)", "data:image/png;base64,AAAA", "//example.com/p.png"})
    @DisplayName("https가 아니거나 비어 있으면 허용하지 않는다")
    void rejectsNonHttpsOrEmpty(String url) {
        assertThat(ProfileImageUrlPolicy.isAllowed(url)).isFalse();
        assertThat(ProfileImageUrlPolicy.allowedOrNull(url)).isNull();
    }
}