package com.example.management.auth.controller;

import com.example.management.auth.exception.AuthExceptionHandler;
import com.example.management.auth.exception.InvalidRefreshTokenException;
import com.example.management.auth.exception.WithdrawnUserException;
import com.example.management.auth.service.AuthService;
import com.example.management.auth.service.LoginResult;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standaloneSetup으로 컨트롤러 + AuthExceptionHandler만 띄우는 통합테스트.
 * KakaoOAuthClient/실제 카카오 서버는 전혀 안 붙고, AuthService를 mock해서
 * 요청 바인딩/응답 직렬화/예외->상태코드 매핑만 검증한다.
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
    @DisplayName("POST /kakao/login: 정상 코드면 토큰과 isNewUser를 반환한다")
    void kakaoLogin_success_returnsTokens() throws Exception {
        when(authService.login("auth-code"))
                .thenReturn(new LoginResult("access-jwt", "refresh-raw", true));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"auth-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-raw"))
                .andExpect(jsonPath("$.isNewUser").value(true));
    }

    @Test
    @DisplayName("POST /kakao/login: 카카오 API 호출이 실패하면 400을 반환한다")
    void kakaoLogin_kakaoApiFails_returns400() throws Exception {
        when(authService.login("bad-code")).thenThrow(new RestClientException("kakao down"));

        mockMvc.perform(post("/api/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("카카오 로그인 처리에 실패했습니다. 다시 시도해주세요."));
    }

    @Test
    @DisplayName("POST /kakao/login: 탈퇴한 사용자면 403을 반환한다")
    void kakaoLogin_withdrawnUser_returns403() throws Exception {
        when(authService.login("code")).thenThrow(new WithdrawnUserException("user-uuid"));

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
    @DisplayName("POST /reissue: 유효한 refresh token이면 새 토큰 쌍을 반환한다")
    void reissue_success_returnsNewTokens() throws Exception {
        when(authService.reissue("old-refresh"))
                .thenReturn(new LoginResult("new-access", "new-refresh", false));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.isNewUser").value(false));
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
