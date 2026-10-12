package com.example.management.auth.service;

import com.example.management.auth.client.SocialOAuthClient;
import com.example.management.auth.client.SocialOAuthClientRegistry;
import com.example.management.auth.client.SocialProfile;
import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.domain.RefreshToken;
import com.example.management.auth.domain.User;
import com.example.management.auth.exception.InvalidRefreshTokenException;
import com.example.management.auth.exception.SignupSessionStoreException;
import com.example.management.auth.exception.WithdrawnUserException;
import com.example.management.auth.jwt.JwtProperties;
import com.example.management.auth.jwt.JwtTokenProvider;
import com.example.management.auth.repository.RefreshTokenRepository;
import com.example.management.auth.repository.UserRepository;
import com.example.management.auth.signup.SignupSession;
import com.example.management.auth.signup.SignupSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private SocialOAuthClientRegistry socialOAuthClientRegistry;
    @Mock
    private SocialOAuthClient kakaoOAuthClient;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private SignupSessionStore signupSessionStore;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // JwtProperties는 record라 굳이 mock하지 않고 실제 값으로 생성한다
        JwtProperties jwtProperties = new JwtProperties("test-secret", 1800L, 1209600L);
        authService = new AuthService(userRepository, refreshTokenRepository, socialOAuthClientRegistry,
                jwtTokenProvider, jwtProperties, signupSessionStore);
    }

    @Test
    @DisplayName("login: 기존 회원이면 프로필을 덮어쓰지 않고 토큰을 발급한다")
    void login_existingUser_doesNotOverwriteProfileAndReturnsTokens() {
        givenKakaoProfile(new SocialProfile("100", "새닉네임", "https://new-image", "new@test.com"));

        User existingUser = User.builder().provider(SocialProvider.KAKAO).socialId("100")
                .nickname("옛닉네임").email("old@test.com").build();
        ReflectionTestUtils.setField(existingUser, "id", 5L);

        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "100")).thenReturn(Optional.of(existingUser));
        when(jwtTokenProvider.generateAccessToken(existingUser.getUserId())).thenReturn("access-jwt");

        LoginResult result = authService.login(SocialProvider.KAKAO, "auth-code", null);

        assertThat(existingUser.getNickname()).isEqualTo("옛닉네임");
        assertThat(existingUser.getEmail()).isEqualTo("old@test.com");
        assertThat(existingUser.getProfileImageUrl()).isNull();

        assertThat(result).isInstanceOf(LoginResult.Authenticated.class);
        AuthTokens tokens = ((LoginResult.Authenticated) result).tokens();
        assertThat(tokens.accessToken()).isEqualTo("access-jwt");
        assertRawRefreshTokenFormat(tokens.refreshToken());

        verify(userRepository, never()).save(any());
        verifyNoInteractions(signupSessionStore);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getTokenHash()).isEqualTo(sha256Hex(tokens.refreshToken()));
    }

    @Test
    @DisplayName("login: 신규 회원이면 회원을 만들지 않고 가입 세션을 저장한 뒤 signupToken과 prefill을 반환한다")
    void login_newUser_startsSignupWithoutCreatingUser() {
        givenKakaoProfile(new SocialProfile("200", "닉네임", "https://image", "user@test.com"));
        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "200")).thenReturn(Optional.empty());
        when(signupSessionStore.save(any(SignupSession.class))).thenReturn("signup-token");

        LoginResult result = authService.login(SocialProvider.KAKAO, "auth-code", null);

        SignupSession expected = new SignupSession(
                SocialProvider.KAKAO, "200", "닉네임", "user@test.com", "https://image");
        verify(signupSessionStore).save(expected);
        assertThat(result).isEqualTo(new LoginResult.SignupRequired("signup-token", expected));

        verify(userRepository, never()).save(any());
        verifyNoInteractions(jwtTokenProvider, refreshTokenRepository);
    }

    @Test
    @DisplayName("login: 신규 회원의 이메일·닉네임·이미지가 없어도 예외 없이 null prefill로 가입 세션을 만든다")
    void login_newUserWithoutEmailAndNickname_startsSignupWithNullPrefill() {
        givenKakaoProfile(new SocialProfile("210", null, null, null));
        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "210")).thenReturn(Optional.empty());
        when(signupSessionStore.save(any(SignupSession.class))).thenReturn("signup-token");

        LoginResult result = authService.login(SocialProvider.KAKAO, "auth-code", null);

        SignupSession expected = new SignupSession(SocialProvider.KAKAO, "210", null, null, null);
        assertThat(result).isEqualTo(new LoginResult.SignupRequired("signup-token", expected));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("login: 소셜 이미지 URL이 https가 아니면 prefill에서 비운다")
    void login_newUserWithNonHttpsImage_dropsImageFromPrefill() {
        givenKakaoProfile(new SocialProfile("220", "닉네임", "http://image", "user@test.com"));
        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "220")).thenReturn(Optional.empty());
        when(signupSessionStore.save(any(SignupSession.class))).thenReturn("signup-token");

        authService.login(SocialProvider.KAKAO, "auth-code", null);

        ArgumentCaptor<SignupSession> captor = ArgumentCaptor.forClass(SignupSession.class);
        verify(signupSessionStore).save(captor.capture());
        assertThat(captor.getValue().profileImageUrl()).isNull();
        assertThat(captor.getValue().nickname()).isEqualTo("닉네임");
    }

    @Test
    @DisplayName("login: 가입 세션 저장에 실패하면 예외가 그대로 전파된다")
    void login_newUser_signupSessionStoreFails_propagates() {
        givenKakaoProfile(new SocialProfile("230", "닉네임", null, "user@test.com"));
        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "230")).thenReturn(Optional.empty());
        when(signupSessionStore.save(any(SignupSession.class)))
                .thenThrow(new SignupSessionStoreException("down", new RuntimeException()));

        assertThrows(SignupSessionStoreException.class, () -> authService.login(SocialProvider.KAKAO, "auth-code", null));

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("login: 탈퇴한 회원이면 WithdrawnUserException을 던지고 토큰도 가입 세션도 만들지 않는다")
    void login_withdrawnUser_throwsWithdrawnUserException() {
        givenKakaoProfile(new SocialProfile("300", "닉네임", "https://image", "user@test.com"));

        User withdrawnUser = User.builder().provider(SocialProvider.KAKAO).socialId("300").build();
        withdrawnUser.withdraw();

        when(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "300")).thenReturn(Optional.of(withdrawnUser));

        assertThrows(WithdrawnUserException.class, () -> authService.login(SocialProvider.KAKAO, "auth-code", null));

        verifyNoInteractions(jwtTokenProvider, signupSessionStore);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("logout: 전달받은 refresh token을 해시해서 삭제를 요청한다")
    void logout_deletesByHashOfProvidedToken() {
        authService.logout("raw-refresh-token");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenRepository).deleteByTokenHash(hashCaptor.capture());
        assertThat(hashCaptor.getValue()).isEqualTo(sha256Hex("raw-refresh-token"));
    }

    @Test
    @DisplayName("reissue: 유효한 refresh token이면 기존 토큰을 삭제하고 새 토큰 쌍을 발급한다")
    void reissue_validToken_rotatesAndReturnsNewTokens() {
        String oldRawToken = "raw-old-token";
        RefreshToken saved = RefreshToken.builder()
                .userId(7L)
                .tokenHash(sha256Hex(oldRawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        User user = User.builder().provider(SocialProvider.KAKAO).socialId("400").build();
        ReflectionTestUtils.setField(user, "id", 7L);

        when(refreshTokenRepository.findByTokenHash(sha256Hex(oldRawToken))).thenReturn(Optional.of(saved));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(user.getUserId())).thenReturn("new-access-jwt");

        AuthTokens result = authService.reissue(oldRawToken);

        assertThat(result.accessToken()).isEqualTo("new-access-jwt");
        assertRawRefreshTokenFormat(result.refreshToken());
        assertThat(result.refreshToken()).isNotEqualTo(oldRawToken);

        verify(refreshTokenRepository).delete(saved);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("reissue: 존재하지 않는 refresh token이면 InvalidRefreshTokenException을 던진다")
    void reissue_tokenNotFound_throwsInvalidRefreshTokenException() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(InvalidRefreshTokenException.class, () -> authService.reissue("unknown-token"));

        verifyNoInteractions(userRepository, jwtTokenProvider);
        verify(refreshTokenRepository, never()).delete(any());
    }

    @Test
    @DisplayName("reissue: 만료된 refresh token이면 삭제 후 InvalidRefreshTokenException을 던진다")
    void reissue_expiredToken_deletesAndThrows() {
        RefreshToken expired = RefreshToken.builder()
                .userId(8L)
                .tokenHash(sha256Hex("expired-token"))
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThrows(InvalidRefreshTokenException.class, () -> authService.reissue("expired-token"));

        verify(refreshTokenRepository, times(1)).delete(expired);
        verifyNoInteractions(userRepository, jwtTokenProvider);
    }

    @Test
    @DisplayName("reissue: 탈퇴한 회원이면 토큰을 삭제하고 WithdrawnUserException을 던진다")
    void reissue_withdrawnUser_deletesTokenAndThrows() {
        RefreshToken saved = RefreshToken.builder()
                .userId(9L)
                .tokenHash(sha256Hex("raw-token"))
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        User withdrawnUser = User.builder().provider(SocialProvider.KAKAO).socialId("500").build();
        ReflectionTestUtils.setField(withdrawnUser, "id", 9L);
        withdrawnUser.withdraw();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(userRepository.findById(9L)).thenReturn(Optional.of(withdrawnUser));

        assertThrows(WithdrawnUserException.class, () -> authService.reissue("raw-token"));

        verify(refreshTokenRepository).delete(saved);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(jwtTokenProvider);
    }

    private void givenKakaoProfile(SocialProfile profile) {
        when(socialOAuthClientRegistry.get(SocialProvider.KAKAO)).thenReturn(kakaoOAuthClient);
        when(kakaoOAuthClient.authenticate("auth-code", null)).thenReturn(profile);
    }

    /** AuthService가 생성하는 원본 refresh token 포맷(SecureRandom 32바이트 → Base64 URL-safe)인지 확인 */
    private void assertRawRefreshTokenFormat(String rawToken) {
        assertThat(Base64.getUrlDecoder().decode(rawToken)).hasSize(32);
    }

    /** AuthService.hash()와 동일한 알고리즘(SHA-256 → hex)을 테스트에서 독립적으로 재현한다 */
    private static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}