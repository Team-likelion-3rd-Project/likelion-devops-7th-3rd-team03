package com.example.management.auth.signup;

import com.example.management.auth.config.SignupProperties;
import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.exception.SignupSessionStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Redis 장애 시 조용히 성공하지 않는지 확인한다. 정상 동작은 SignupSessionStoreRedisTest가 실제 Redis로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SignupSessionStoreTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SignupSessionStore store;

    @BeforeEach
    void setUp() {
        store = new SignupSessionStore(stringRedisTemplate, JsonMapper.builder().build(), new SignupProperties(null));
    }

    @Test
    @DisplayName("save: Redis 장애가 나면 토큰을 반환하지 않고 SignupSessionStoreException을 던진다")
    void save_throws_whenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.save(sampleSession()))
                .isInstanceOf(SignupSessionStoreException.class)
                .hasCauseInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("consume: Redis 장애가 나면 empty가 아니라 SignupSessionStoreException을 던진다")
    void consume_throws_whenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> store.consume("some-token"))
                .isInstanceOf(SignupSessionStoreException.class)
                .hasCauseInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("consume: 토큰이 비어 있으면 Redis를 호출하지 않고 empty를 반환한다")
    void consume_returnsEmpty_whenTokenBlank() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume(" ")).isEmpty();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("SignupProperties: session-ttl이 없으면 15분을 쓴다")
    void properties_defaultTtlIs15Minutes() {
        assertThat(new SignupProperties(null).sessionTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    private SignupSession sampleSession() {
        return new SignupSession(SocialProvider.GOOGLE, "google-123", "홍길동", "hong@example.com", null);
    }
}