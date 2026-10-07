package com.example.management.auth.service;

import com.example.management.auth.client.KakaoOAuthClient;
import com.example.management.auth.client.dto.KakaoTokenResponse;
import com.example.management.auth.client.dto.KakaoUserResponse;
import com.example.management.auth.domain.RefreshToken;
import com.example.management.auth.domain.User;
import com.example.management.auth.exception.InvalidRefreshTokenException;
import com.example.management.auth.exception.WithdrawnUserException;
import com.example.management.auth.jwt.JwtProperties;
import com.example.management.auth.jwt.JwtTokenProvider;
import com.example.management.auth.repository.RefreshTokenRepository;
import com.example.management.auth.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private KakaoOAuthClient kakaoOAuthClient;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // JwtProperties는 record라 굳이 mock하지 않고 실제 값으로 생성한다
        JwtProperties jwtProperties = new JwtProperties("test-secret", 1800L, 1209600L);
        authService = new AuthService(userRepository, refreshTokenRepository, kakaoOAuthClient, jwtTokenProvider, jwtProperties);
    }

    @Test
    @DisplayName("login: 기존 회원이면 프로필을 갱신하고 isNewUser=false를 반환한다")
    void login_existingUser_updatesProfileAndReturnsTokens() {
        KakaoTokenResponse kakaoToken = new KakaoTokenResponse("kakao-access-token", "bearer", 3600);
        KakaoUserResponse kakaoUser = kakaoUserResponse(100L, "새닉네임", "https://new-image", "new@test.com");

        User existingUser = User.builder().kakaoId(100L).nickname("옛닉네임").email("old@test.com").build();
        ReflectionTestUtils.setField(existingUser, "id", 5L);

        when(kakaoOAuthClient.exchangeToken(eq("auth-code"))).thenReturn(kakaoToken);
        when(kakaoOAuthClient.getUserInfo(eq("kakao-access-token"))).thenReturn(kakaoUser);
        when(userRepository.findByKakaoId(100L)).thenReturn(Optional.of(existingUser));
        when(jwtTokenProvider.generateAccessToken(existingUser.getUserId())).thenReturn("access-jwt");

        LoginResult result = authService.login("auth-code");

        assertThat(existingUser.getNickname()).isEqualTo("새닉네임");
        assertThat(existingUser.getEmail()).isEqualTo("new@test.com");
        assertThat(existingUser.getProfileImageUrl()).isEqualTo("https://new-image");
        assertThat(result.isNewUser()).isFalse();
        assertThat(result.accessToken()).isEqualTo("access-jwt");
        assertRawRefreshTokenFormat(result.refreshToken());

        verify(userRepository, never()).save(any());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getTokenHash()).isEqualTo(sha256Hex(result.refreshToken()));
    }

    @Test
    @DisplayName("login: 신규 회원이면 User를 생성하고 isNewUser=true를 반환한다")
    void login_newUser_createsUserAndReturnsIsNewUserTrue() {
        KakaoTokenResponse kakaoToken = new KakaoTokenResponse("kakao-access-token", "bearer", 3600);
        KakaoUserResponse kakaoUser = kakaoUserResponse(200L, "닉네임", "https://image", "user@test.com");

        when(kakaoOAuthClient.exchangeToken(anyString())).thenReturn(kakaoToken);
        when(kakaoOAuthClient.getUserInfo(anyString())).thenReturn(kakaoUser);
        when(userRepository.findByKakaoId(200L)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 10L);
            return saved;
        });
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("access-jwt");

        LoginResult result = authService.login("auth-code");

        assertThat(result.isNewUser()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getKakaoId()).isEqualTo(200L);
        assertThat(userCaptor.getValue().getNickname()).isEqualTo("닉네임");
    }

    @Test
    @DisplayName("login: 탈퇴한 회원이면 WithdrawnUserException을 던지고 토큰을 발급하지 않는다")
    void login_withdrawnUser_throwsWithdrawnUserException() {
        KakaoTokenResponse kakaoToken = new KakaoTokenResponse("kakao-access-token", "bearer", 3600);
        KakaoUserResponse kakaoUser = kakaoUserResponse(300L, "닉네임", "https://image", "user@test.com");

        User withdrawnUser = User.builder().kakaoId(300L).build();
        withdrawnUser.withdraw();

        when(kakaoOAuthClient.exchangeToken(anyString())).thenReturn(kakaoToken);
        when(kakaoOAuthClient.getUserInfo(anyString())).thenReturn(kakaoUser);
        when(userRepository.findByKakaoId(300L)).thenReturn(Optional.of(withdrawnUser));

        assertThrows(WithdrawnUserException.class, () -> authService.login("auth-code"));

        verifyNoInteractions(jwtTokenProvider);
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

        User user = User.builder().kakaoId(400L).build();
        ReflectionTestUtils.setField(user, "id", 7L);

        when(refreshTokenRepository.findByTokenHash(sha256Hex(oldRawToken))).thenReturn(Optional.of(saved));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(user.getUserId())).thenReturn("new-access-jwt");

        LoginResult result = authService.reissue(oldRawToken);

        assertThat(result.accessToken()).isEqualTo("new-access-jwt");
        assertThat(result.isNewUser()).isFalse();
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

        User withdrawnUser = User.builder().kakaoId(500L).build();
        ReflectionTestUtils.setField(withdrawnUser, "id", 9L);
        withdrawnUser.withdraw();

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(saved));
        when(userRepository.findById(9L)).thenReturn(Optional.of(withdrawnUser));

        assertThrows(WithdrawnUserException.class, () -> authService.reissue("raw-token"));

        verify(refreshTokenRepository).delete(saved);
        verify(refreshTokenRepository, never()).save(any());
        verifyNoInteractions(jwtTokenProvider);
    }

    private KakaoUserResponse kakaoUserResponse(Long id, String nickname, String profileImageUrl, String email) {
        return new KakaoUserResponse(
                id,
                new KakaoUserResponse.KakaoAccount(email, new KakaoUserResponse.KakaoAccount.Profile(nickname, profileImageUrl))
        );
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
