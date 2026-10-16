package com.example.management.auth.controller;

import com.example.management.auth.client.GoogleOAuthClient;
import com.example.management.auth.client.KakaoOAuthClient;
import com.example.management.auth.client.SocialProfile;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 → 가입 → 재로그인 전체 흐름을 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * provider 호출(authenticate)만 스파이로 대체하고, 레지스트리·세션 저장소·DB는 실제 빈을 쓴다.
 * 구글 클라이언트는 client-id가 있어야 등록되므로 테스트 속성으로 켜고, 네이버는 꺼진 상태로 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "google.client-id=it-google")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialLoginFlowIntegrationTest {

    /** 테스트가 만든 회원만 지우기 위한 social_id 접두사 */
    private static final String SOCIAL_ID_PREFIX = "it-flow-";
    private static final String IMAGE_URL = "https://img.test.com/profile.png";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private KakaoOAuthClient kakaoOAuthClient;
    @MockitoSpyBean
    private GoogleOAuthClient googleOAuthClient;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM (
                    SELECT id FROM users WHERE social_id LIKE ?) t)
                """, SOCIAL_ID_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE social_id LIKE ?", SOCIAL_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("카카오 신규 사용자는 로그인 시 회원이 생기지 않고, 가입 후 재로그인하면 토큰이 발급된다")
    void kakao_loginSignupRelogin() throws Exception {
        String socialId = newSocialId();
        givenKakaoProfile(new SocialProfile(socialId, "카카오닉", IMAGE_URL, "kakao@test.com"));

        String signupToken = readString(login("kakao")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.prefill.nickname").value("카카오닉"))
                .andExpect(jsonPath("$.prefill.email").value("kakao@test.com"))
                .andExpect(jsonPath("$.prefill.profileImageUrl").value(IMAGE_URL))
                .andExpect(jsonPath("$.accessToken").doesNotExist()), "$.signupToken");

        assertThat(countUsers("KAKAO", socialId)).isZero();

        signup(signupToken, "카카오닉", "kakao@test.com", IMAGE_URL)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        login("kakao")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.signupToken").doesNotExist())
                .andExpect(jsonPath("$.prefill").doesNotExist());

        assertThat(countUsers("KAKAO", socialId)).isEqualTo(1);
    }

    @Test
    @DisplayName("재로그인 때 소셜 프로필이 바뀌어도 가입 때 입력한 회원 정보를 덮어쓰지 않는다")
    void relogin_doesNotOverwriteProfile() throws Exception {
        String socialId = newSocialId();
        signUpKakao(socialId, "직접입력닉", "mine@test.com");

        givenKakaoProfile(new SocialProfile(socialId, "바뀐소셜닉", "https://img.test.com/new.png", "changed@test.com"));
        login("kakao").andExpect(jsonPath("$.signupRequired").value(false));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT nickname, email, profile_image_url FROM users WHERE provider = 'KAKAO' AND social_id = ?", socialId))
                .containsEntry("nickname", "직접입력닉")
                .containsEntry("email", "mine@test.com")
                .containsEntry("profile_image_url", null);
    }

    @Test
    @DisplayName("구글도 같은 흐름으로 가입되고, 카카오와 social_id가 같아도 별도 회원이 된다")
    void google_sameSocialIdAsKakao_isSeparateUser() throws Exception {
        String socialId = newSocialId();
        signUpKakao(socialId, "카카오회원", "kakao@test.com");

        doReturn(new SocialProfile(socialId, "구글닉", IMAGE_URL, "google@test.com"))
                .when(googleOAuthClient).authenticate(anyString(), any());

        String signupToken = readString(login("google")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.prefill.email").value("google@test.com")), "$.signupToken");

        signup(signupToken, "구글회원", "google@test.com", null).andExpect(status().isOk());
        login("google").andExpect(jsonPath("$.signupRequired").value(false));

        assertThat(countUsers("KAKAO", socialId)).isEqualTo(1);
        assertThat(countUsers("GOOGLE", socialId)).isEqualTo(1);
    }

    @Test
    @DisplayName("소셜에서 이메일을 받지 못하면 prefill.email이 null이고, 이메일 없이는 가입할 수 없다")
    void noEmailFromProvider_requiresEmailOnSignup() throws Exception {
        String socialId = newSocialId();
        givenKakaoProfile(new SocialProfile(socialId, "닉네임", null, null));

        String signupToken = readString(login("kakao")
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.prefill.email").doesNotExist())
                .andExpect(jsonPath("$.prefill.profileImageUrl").doesNotExist()), "$.signupToken");

        signup(signupToken, "닉네임", "", null).andExpect(status().isBadRequest());
        signup(signupToken, "닉네임", "typed@test.com", null).andExpect(status().isOk());

        assertThat(countUsers("KAKAO", socialId)).isEqualTo(1);
    }

    @Test
    @DisplayName("소셜 이미지 URL이 https가 아니면 prefill에서 비운다")
    void insecureImageUrl_isClearedInPrefill() throws Exception {
        givenKakaoProfile(new SocialProfile(newSocialId(), "닉네임", "http://img.test.com/p.png", "a@test.com"));

        login("kakao")
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.prefill.profileImageUrl").doesNotExist());
    }

    @Test
    @DisplayName("저장소에 없는(만료된) 가입 토큰으로 가입하면 410이다")
    void unknownSignupToken_isGone() throws Exception {
        signup(UUID.randomUUID().toString(), "닉네임", "a@test.com", null)
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("비활성 provider(naver)와 모르는 provider로 로그인하면 400이다")
    void disabledOrUnknownProvider_isBadRequest() throws Exception {
        login("naver").andExpect(status().isBadRequest());
        login("unknown").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("탈퇴한 회원이 로그인하면 403이다")
    void withdrawnUser_isForbidden() throws Exception {
        String socialId = newSocialId();
        signUpKakao(socialId, "탈퇴예정", "bye@test.com");
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN' WHERE provider = 'KAKAO' AND social_id = ?", socialId);

        login("kakao").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("로그아웃한 refresh token으로는 재발급할 수 없다")
    void logout_revokesRefreshToken() throws Exception {
        String refreshToken = signUpKakao(newSocialId(), "닉네임", "a@test.com");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshTokenBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인 요청의 code와 state가 provider 클라이언트까지 그대로 전달된다")
    void codeAndState_arePassedToClient() throws Exception {
        givenKakaoProfile(new SocialProfile(newSocialId(), "닉네임", null, "a@test.com"));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"auth-code-1","state":"state-xyz"}
                                """))
                .andExpect(status().isOk());

        verify(kakaoOAuthClient).authenticate("auth-code-1", "state-xyz");
    }

    /** 카카오로 로그인 → 가입까지 마치고 발급된 refresh token을 돌려준다. 가입 시 이미지는 비운다 */
    private String signUpKakao(String socialId, String nickname, String email) throws Exception {
        givenKakaoProfile(new SocialProfile(socialId, nickname, null, email));
        String signupToken = readString(login("kakao"), "$.signupToken");
        return readString(signup(signupToken, nickname, email, null).andExpect(status().isOk()), "$.refreshToken");
    }

    private void givenKakaoProfile(SocialProfile profile) {
        doReturn(profile).when(kakaoOAuthClient).authenticate(anyString(), any());
    }

    private ResultActions login(String provider) throws Exception {
        return mockMvc.perform(post("/api/auth/{provider}/login", provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"auth-code","state":"state"}
                        """));
    }

    private ResultActions signup(String signupToken, String nickname, String email, String profileImageUrl) throws Exception {
        String imageJson = profileImageUrl == null ? "null" : "\"" + profileImageUrl + "\"";
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signupToken":"%s","nickname":"%s","email":"%s","profileImageUrl":%s}
                        """.formatted(signupToken, nickname, email, imageJson)));
    }

    private static String readString(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static String refreshTokenBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }

    private static String newSocialId() {
        return SOCIAL_ID_PREFIX + UUID.randomUUID();
    }

    private Integer countUsers(String provider, String socialId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE provider = ? AND social_id = ?", Integer.class, provider, socialId);
    }
}
