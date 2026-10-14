package com.example.management.auth.controller;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.signup.SignupSession;
import com.example.management.auth.signup.SignupSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 가입 완료 API를 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * access token 없이 호출할 수 있는지(permitAll), 세션 일회용, 유니크 위반 처리가 실제 DB에서 동작하는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupIntegrationTest {

    /** 테스트가 만든 회원만 지우기 위한 social_id 접두사 */
    private static final String SOCIAL_ID_PREFIX = "it-signup-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SignupSessionStore signupSessionStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM (
                    SELECT id FROM users WHERE social_id LIKE ?) t)
                """, SOCIAL_ID_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE social_id LIKE ?", SOCIAL_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("access token 없이 가입하면 회원이 생성되고 토큰이 발급되며, 같은 가입 토큰은 다시 쓸 수 없다")
    void signup_createsUserAndTokenIsSingleUse() throws Exception {
        String socialId = newSocialId();
        String signupToken = newSignupToken(socialId);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(signupToken, "닉네임", "user@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertThat(countUsers(socialId)).isEqualTo(1);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(signupToken, "닉네임", "user@test.com")))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("같은 닉네임을 가진 두 사용자가 모두 가입된다")
    void signup_sameNickname_bothSucceed() throws Exception {
        String firstSocialId = newSocialId();
        String secondSocialId = newSocialId();

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(newSignupToken(firstSocialId), "같은닉네임", "first@test.com")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(newSignupToken(secondSocialId), "같은닉네임", "second@test.com")))
                .andExpect(status().isOk());

        assertThat(countUsers(firstSocialId)).isEqualTo(1);
        assertThat(countUsers(secondSocialId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 소셜 계정의 가입 토큰 두 개로 가입하면 두 번째는 먼저 가입된 회원으로 로그인 처리된다")
    void signup_duplicateSocialAccount_secondRequestLogsIn() throws Exception {
        String socialId = newSocialId();
        String firstToken = newSignupToken(socialId);
        String secondToken = newSignupToken(socialId);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(firstToken, "먼저가입", "first@test.com")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(secondToken, "나중입력", "second@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(countUsers(socialId)).isEqualTo(1);
        String nickname = jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE provider = 'KAKAO' AND social_id = ?", String.class, socialId);
        assertThat(nickname).isEqualTo("먼저가입");
    }

    @Test
    @DisplayName("입력 검증에 실패하면 400이고 가입 세션은 소비되지 않아 다시 제출할 수 있다")
    void signup_invalidInput_keepsSession() throws Exception {
        String socialId = newSocialId();
        String signupToken = newSignupToken(socialId);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(signupToken, "닉네임", "not-an-email")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody(signupToken, "닉네임", "user@test.com")))
                .andExpect(status().isOk());

        assertThat(countUsers(socialId)).isEqualTo(1);
    }

    private static String newSocialId() {
        return SOCIAL_ID_PREFIX + UUID.randomUUID();
    }

    private String newSignupToken(String socialId) {
        return signupSessionStore.save(new SignupSession(SocialProvider.KAKAO, socialId, null, null, null));
    }

    private Integer countUsers(String socialId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE provider = 'KAKAO' AND social_id = ?", Integer.class, socialId);
    }

    private static String signupBody(String signupToken, String nickname, String email) {
        return """
                {"signupToken":"%s","nickname":"%s","email":"%s","profileImageUrl":null}
                """.formatted(signupToken, nickname, email);
    }
}