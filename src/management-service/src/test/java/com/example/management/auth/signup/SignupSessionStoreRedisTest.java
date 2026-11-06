package com.example.management.auth.signup;

import com.example.management.auth.config.SignupProperties;
import com.example.management.auth.domain.SocialProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Redis로 저장·조회·만료·재사용 불가를 검증한다. 로컬은 docker compose의 redis, CI는 서비스 컨테이너를 쓴다.
 * 저장소만 검증하므로 스프링 컨텍스트 없이 연결을 직접 만든다.
 */
class SignupSessionStoreRedisTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeAll
    static void connect() {
        String host = envOrDefault("REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOrDefault("REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("저장한 세션을 토큰으로 조회하면 같은 값이 나온다")
    void savedSessionCanBeConsumed() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));
        SignupSession session = new SignupSession(SocialProvider.GOOGLE,
                "google-123", "홍길동", "hong@example.com", "https://example.com/p.png");

        String token = store.save(session);

        assertThat(store.consume(token)).contains(session);
    }

    @Test
    @DisplayName("prefill 값이 null이어도 그대로 저장·조회된다")
    void nullPrefillValuesArePreserved() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));
        SignupSession session = new SignupSession(SocialProvider.KAKAO, "12345", null, null, null);

        String token = store.save(session);

        assertThat(store.consume(token)).contains(session);
    }

    @Test
    @DisplayName("한 번 소비한 토큰으로 다시 조회하면 empty다")
    void consumedTokenCannotBeReused() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));
        String token = store.save(sampleSession());

        store.consume(token);

        assertThat(store.consume(token)).isEmpty();
        assertThat(redisTemplate.hasKey(key(token))).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나면 조회되지 않는다")
    void expiredSessionIsNotFound() throws InterruptedException {
        SignupSessionStore store = storeWithTtl(Duration.ofSeconds(1));
        String token = store.save(sampleSession());

        Thread.sleep(1_500);

        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    @DisplayName("저장한 키에는 설정한 TTL이 걸린다")
    void savedKeyHasConfiguredTtl() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));
        String token = store.save(sampleSession());

        Long ttlSeconds = redisTemplate.getExpire(key(token), TimeUnit.SECONDS);

        assertThat(ttlSeconds).isBetween(1L, 900L);
        redisTemplate.delete(key(token));
    }

    @Test
    @DisplayName("토큰은 매번 다른 43자 Base64URL 문자열이다")
    void tokensAreRandomBase64Url() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));

        String first = store.save(sampleSession());
        String second = store.save(sampleSession());

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");
        redisTemplate.delete(key(first));
        redisTemplate.delete(key(second));
    }

    @Test
    @DisplayName("존재하지 않는 토큰이면 empty다")
    void unknownTokenIsEmpty() {
        SignupSessionStore store = storeWithTtl(Duration.ofMinutes(15));

        assertThat(store.consume("unknown-token")).isEmpty();
    }

    private SignupSessionStore storeWithTtl(Duration ttl) {
        return new SignupSessionStore(redisTemplate, jsonMapper, new SignupProperties(ttl));
    }

    private SignupSession sampleSession() {
        return new SignupSession(SocialProvider.GOOGLE, "google-123", "홍길동", "hong@example.com", null);
    }

    private static String key(String token) {
        return "signup:" + token;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}