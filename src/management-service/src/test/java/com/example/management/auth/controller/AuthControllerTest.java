package com.example.management.auth.controller;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.AuthExceptionHandler;
import com.example.management.auth.exception.InvalidRefreshTokenException;
import com.example.management.auth.exception.SignupSessionStoreException;
import com.example.management.auth.exception.WithdrawnUserException;
import com.example.management.auth.service.AuthService;
import com.example.management.auth.service.AuthTokens;
import com.example.management.auth.service.LoginResult;
import com.example.management.auth.signup.SignupSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClientException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standaloneSetup으로 컨트롤러 + AuthExceptionHandler만 띄우는 통합테스트.
 * KakaoOAuthClient/실제 카카오 서버는 전혀 안 붙고, AuthService를 mock해서 요청 바인딩/응답 직렬화/예외->상태코드 매핑만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController controller = new AuthController(authService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    // ---------------------------------------------------------------
    // POST /api/auth/kakao/login
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POST /kakao/login: 기존 회원이면 signupRequired=false와 토큰만 반환한다")
    void kakaoLogin_existingUser_returnsTokens() throws Exception {
        when(authService.login(SocialProvider.KAKAO, "auth-code", null))
                .thenReturn(new LoginResult.Authenticated(new AuthTokens("access-jwt", "refresh-raw")));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(false))
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-raw"))
                .andExpect(jsonPath("$.signupToken").doesNotExist())
                .andExpect(jsonPath("$.prefill").doesNotExist())
                .andExpect(jsonPath("$.isNewUser").doesNotExist());
    }

    @Test
    @DisplayName("POST /kakao/login: 신규 회원이면 signupRequired=true와 signupToken·prefill을 반환하고 토큰은 주지 않는다")
    void kakaoLogin_newUser_returnsSignupTokenAndPrefill() throws Exception {
        SignupSession session = new SignupSession(SocialProvider.KAKAO, "200", "닉네임", null, "https://image");
        when(authService.login(SocialProvider.KAKAO, "auth-code", null))
                .thenReturn(new LoginResult.SignupRequired("signup-token", session));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signupRequired").value(true))
                .andExpect(jsonPath("$.signupToken").value("signup-token"))
                .andExpect(jsonPath("$.prefill.nickname").value("닉네임"))
                .andExpect(jsonPath("$.prefill.email").value(nullValue()))
                .andExpect(jsonPath("$.prefill.profileImageUrl").value("https://image"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.prefill.provider").doesNotExist())
                .andExpect(jsonPath("$.prefill.socialId").doesNotExist());
    }

    @Test
    @DisplayName("POST /kakao/login: 가입 세션 저장소 장애면 500을 반환한다")
    void kakaoLogin_signupSessionStoreFails_returns500() throws Exception {
        when(authService.login(SocialProvider.KAKAO, "auth-code", null))
                .thenThrow(new SignupSessionStoreException("down", new RuntimeException()));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("일시적인 오류로 가입을 진행할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }

    @Test
    @DisplayName("POST /kakao/login: 카카오 API 호출이 실패하면 400을 반환한다")
    void kakaoLogin_kakaoApiFails_returns400() throws Exception {
        when(authService.login(SocialProvider.KAKAO, "bad-code", null)).thenThrow(new RestClientException("kakao down"));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("소셜 로그인 처리에 실패했습니다. 다시 시도해주세요."));
    }

    @Test
    @DisplayName("POST /kakao/login: 탈퇴한 사용자면 403을 반환한다")
    void kakaoLogin_withdrawnUser_returns403() throws Exception {
        when(authService.login(SocialProvider.KAKAO, "code", null)).thenThrow(new WithdrawnUserException("user-uuid"));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------
    // POST /api/auth/logout
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POST /logout: 전달받은 refresh token으로 로그아웃 처리하고 204를 반환한다")
    void logout_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"raw-refresh\"}"))
                .andExpect(status().isNoContent());

        verify(authService).logout("raw-refresh");
    }

    // ---------------------------------------------------------------
    // POST /api/auth/reissue
    // ---------------------------------------------------------------

    @Test
    @DisplayName("POST /reissue: 유효한 refresh token이면 새 토큰 쌍만 반환한다")
    void reissue_success_returnsNewTokens() throws Exception {
        when(authService.reissue("old-refresh")).thenReturn(new AuthTokens("new-access", "new-refresh"));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.isNewUser").doesNotExist())
                .andExpect(jsonPath("$.signupRequired").doesNotExist());
    }

    @Test
    @DisplayName("POST /reissue: 유효하지 않은 refresh token이면 401을 반환한다")
    void reissue_invalidToken_returns401() throws Exception {
        when(authService.reissue("bad-token")).thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /reissue: 탈퇴한 사용자면 403을 반환한다")
    void reissue_withdrawnUser_returns403() throws Exception {
        when(authService.reissue("token")).thenThrow(new WithdrawnUserException("user-uuid"));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"token\"}"))
                .andExpect(status().isForbidden());
    }
}