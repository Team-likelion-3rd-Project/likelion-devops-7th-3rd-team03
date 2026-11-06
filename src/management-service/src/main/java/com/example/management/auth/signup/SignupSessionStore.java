package com.example.management.auth.signup;

import com.example.management.auth.config.SignupProperties;
import com.example.management.auth.exception.SignupSessionStoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 가입 대기 세션 저장소. signup:{무작위 토큰} 키에 JSON 문자열로 저장하고, 조회와 삭제를 한 번에 처리해 일회용으로 쓴다.
 * 링크 캐시와 달리 대체 경로가 없으므로 Redis 장애를 삼키지 않고 SignupSessionStoreException으로 올려 서버 오류로 응답한다.
 */
@Component
@RequiredArgsConstructor
public class SignupSessionStore {

    private static final String KEY_PREFIX = "signup:";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final SignupProperties properties;

    /** 세션을 저장하고 발급한 토큰을 반환한다. 값과 TTL을 한 명령으로 설정해 TTL 없는 키가 남지 않는다. */
    public String save(SignupSession session) {
        String token = newToken();
        String value = jsonMapper.writeValueAsString(session);
        try {
            stringRedisTemplate.opsForValue().set(key(token), value, properties.sessionTtl());
        } catch (DataAccessException e) {
            throw new SignupSessionStoreException("가입 대기 세션을 저장하지 못했습니다.", e);
        }
        return token;
    }
    /**
     * 토큰에 해당하는 세션을 꺼내면서 삭제한다(GETDEL). 만료됐거나 이미 소비했거나 없는 토큰이면 empty다.
     * Redis는 세 경우를 구분하지 못하므로 호출하는 쪽은 모두 같은 오류로 다룬다.
     */
    public Optional<SignupSession> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String value;
        try {
            value = stringRedisTemplate.opsForValue().getAndDelete(key(token));
        } catch (DataAccessException e) {
            throw new SignupSessionStoreException("가입 대기 세션을 조회하지 못했습니다.", e);
        }
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(jsonMapper.readValue(value, SignupSession.class));
        } catch (JacksonException e) {
            throw new SignupSessionStoreException("가입 대기 세션 값을 읽지 못했습니다.", e);
        }
    }

    // JWT가 아닌 임의 문자열이라 API 인증 토큰으로 오인되지 않는다. 32바이트 → Base64URL 43자
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}