package com.example.management.auth.service;

import com.example.management.auth.client.SocialOAuthClientRegistry;
import com.example.management.auth.client.SocialProfile;
import com.example.management.auth.domain.RefreshToken;
import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.domain.User;
import com.example.management.auth.exception.InvalidRefreshTokenException;
import com.example.management.auth.exception.SignupSessionExpiredException;
import com.example.management.auth.exception.WithdrawnUserException;
import com.example.management.auth.jwt.JwtProperties;
import com.example.management.auth.jwt.JwtTokenProvider;
import com.example.management.auth.repository.RefreshTokenRepository;
import com.example.management.auth.repository.UserRepository;
import com.example.management.auth.signup.ProfileImageUrlPolicy;
import com.example.management.auth.signup.SignupSession;
import com.example.management.auth.signup.SignupSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int REFRESH_TOKEN_BYTE_LENGTH = 32; // 256비트
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialOAuthClientRegistry socialOAuthClientRegistry;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final SignupSessionStore signupSessionStore;

    /**
     * 가입된 회원이면 토큰을 발급한다. 소셜 프로필로 기존 회원 정보를 덮어쓰지 않는다.
     * 미가입이면 회원을 만들지 않고 가입 대기 세션을 저장한 뒤 signupToken과 prefill을 돌려준다.
     * prefill 값은 소셜 응답에 없으면 null이며, 사용자가 추가정보 페이지에서 채우고 가입 API가 검증한다.
     */
    @Transactional
    public LoginResult login(SocialProvider provider, String code, String state) {
        SocialProfile profile = socialOAuthClientRegistry.get(provider).authenticate(code, state);

        Optional<User> existingUser = userRepository.findByProviderAndSocialId(provider, profile.socialId());
        if (existingUser.isEmpty()) {
            return startSignup(provider, profile);
        }

        User user = existingUser.get();
        if (!user.isActive()) {
            throw new WithdrawnUserException(user.getUserId());
        }

        return new LoginResult.Authenticated(issueTokens(user));
    }

    private LoginResult startSignup(SocialProvider provider, SocialProfile profile) {
        SignupSession session = new SignupSession(
                provider,
                profile.socialId(),
                profile.nickname(),
                profile.email(),
                // 가입 API가 거부할 이미지 URL은 미리 비워서 FE가 기본 아이콘을 보여주게 한다
                ProfileImageUrlPolicy.allowedOrNull(profile.profileImageUrl())
        );
        String signupToken = signupSessionStore.save(session);
        return new LoginResult.SignupRequired(signupToken, session);
    }

    /**
     * 가입 대기 세션을 소비해 회원을 만들고 토큰을 발급한다. 입력값은 컨트롤러에서 검증을 마친 상태로 들어온다.
     * provider·socialId는 세션 값만 쓰고, 닉네임·이메일·이미지는 사용자가 입력한 값을 쓴다.
     * 유니크 위반 뒤에는 같은 트랜잭션에서 다시 조회할 수 없으므로 메서드 전체를 트랜잭션으로 묶지 않는다
     * (저장·조회·토큰 저장은 각각 리포지토리 트랜잭션으로 실행된다).
     */
    public AuthTokens signup(String signupToken, String nickname, String email, String profileImageUrl) {
        SignupSession session = signupSessionStore.consume(signupToken)
                .orElseThrow(SignupSessionExpiredException::new);

        User user = createUserOrFindExisting(session, nickname, email, profileImageUrl);
        if (!user.isActive()) {
            throw new WithdrawnUserException(user.getUserId());
        }

        return issueTokens(user);
    }

    private User createUserOrFindExisting(SignupSession session, String nickname, String email, String profileImageUrl) {
        User newUser = User.builder()
                .provider(session.provider())
                .socialId(session.socialId())
                .nickname(nickname)
                .email(email)
                .profileImageUrl(StringUtils.hasLength(profileImageUrl) ? profileImageUrl : null)
                .build();
        try {
            return userRepository.saveAndFlush(newUser);
        } catch (DataIntegrityViolationException e) {
            // 다른 탭에서 같은 소셜 계정으로 먼저 가입이 끝난 경우: 먼저 가입된 회원으로 로그인 처리한다
            return userRepository.findByProviderAndSocialId(session.provider(), session.socialId())
                    .orElseThrow(() -> e);
        }
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
    public AuthTokens reissue(String refreshToken) {
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

        return issueTokens(user);
    }

    private AuthTokens issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId());
        String rawRefreshToken = generateRefreshToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hash(rawRefreshToken))
                .expiresAt(LocalDateTime.now().plusSeconds(jwtProperties.refreshTokenValiditySeconds()))
                .build());

        return new AuthTokens(accessToken, rawRefreshToken);
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
