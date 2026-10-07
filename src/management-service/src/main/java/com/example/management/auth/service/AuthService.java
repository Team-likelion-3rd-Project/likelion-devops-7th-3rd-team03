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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int REFRESH_TOKEN_BYTE_LENGTH = 32; // 256비트
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    @Transactional
    public LoginResult login(String authorizationCode) {
        KakaoTokenResponse kakaoToken = kakaoOAuthClient.exchangeToken(authorizationCode);
        KakaoUserResponse kakaoUser = kakaoOAuthClient.getUserInfo(kakaoToken.accessToken());

        var existingUser = userRepository.findByKakaoId(kakaoUser.id());
        boolean isNewUser = existingUser.isEmpty();

        User user = existingUser
                .map(existing -> {
                    existing.updateProfile(kakaoUser.nickname(), kakaoUser.profileImageUrl(), kakaoUser.email());
                    return existing;
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .kakaoId(kakaoUser.id())
                        .nickname(kakaoUser.nickname())
                        .profileImageUrl(kakaoUser.profileImageUrl())
                        .email(kakaoUser.email())
                        .build()));

        if (!user.isActive()) {
            throw new WithdrawnUserException(user.getUserId());
        }

        return issueTokens(user, isNewUser);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(hash(refreshToken));
    }

    /**
     * refresh token으로 새 access/refresh token을 발급한다. 사용된 refresh token은 즉시
     * 폐기(rotate)한다.
     * 만료/탈퇴 케이스는 실패로 응답해야 하지만, 그 과정에서 실행한 delete(폐기)는 커밋되어야 한다 —
     * InvalidRefreshTokenException/WithdrawnUserException은 기본 롤백 대상(unchecked)이라
     * noRollbackFor로 명시하지 않으면 delete까지 함께 롤백되어 폐기됐어야 할 토큰이 남는다.
     */
    @Transactional(noRollbackFor = { InvalidRefreshTokenException.class, WithdrawnUserException.class })
    public LoginResult reissue(String refreshToken) {
        RefreshToken saved = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (saved.isExpired()) {
            refreshTokenRepository.delete(saved);
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(saved.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        refreshTokenRepository.delete(saved);

        if (!user.isActive()) {
            throw new WithdrawnUserException(user.getUserId());
        }

        return issueTokens(user, false);
    }

    private LoginResult issueTokens(User user, boolean isNewUser) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId());
        String rawRefreshToken = generateRefreshToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hash(rawRefreshToken))
                .expiresAt(LocalDateTime.now().plusSeconds(jwtProperties.refreshTokenValiditySeconds()))
                .build());

        return new LoginResult(accessToken, rawRefreshToken, isNewUser);
    }

    /** SecureRandom 32바이트를 Base64 URL-safe(패딩 없음)로 인코딩한 원본 refresh token */
    private String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** DB에는 원본 대신 SHA-256 해시만 저장한다 */
    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다", e);
        }
    }
}
