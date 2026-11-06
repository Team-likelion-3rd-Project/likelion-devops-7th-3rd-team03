package redirect_service.redirect;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import redirect_service.config.RedirectProperties;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRedirectCacheTest {

    private static final String KEY = "link:demo";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisRedirectCache redirectCache;

    @BeforeEach
    void setUp() {
        RedirectProperties properties = new RedirectProperties();
        properties.setCacheTtl(Duration.ofMinutes(10));
        redirectCache = new RedisRedirectCache(stringRedisTemplate, properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Redis Hash를 리다이렉트 캐시 엔트리로 변환한다")
    void returnsEntryFromHash() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(KEY)).thenReturn(Map.of(
                "linkId", "1",
                "originalUrl", "https://example.com",
                "isEnabled", "true",
                "expiresAt", ""
        ));

        RedirectCacheEntry entry = redirectCache.get("demo").orElseThrow();

        assertThat(entry.linkId()).isEqualTo(1L);
        assertThat(entry.originalUrl()).isEqualTo("https://example.com");
        assertThat(entry.isEnabled()).isTrue();
        assertThat(entry.expiresAt()).isNull();
    }

    @Test
    @DisplayName("캐시 엔트리를 Lua 스크립트로 Hash 네 필드와 TTL로 저장한다")
    void storesEntryAsHashWithTtl() {
        RedirectCacheEntry entry = new RedirectCacheEntry(1L, "https://example.com", true, null);

        redirectCache.put("demo", entry);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(KEY)),
                eq("1"),
                eq("https://example.com"),
                eq("true"),
                eq(""),
                eq("600")
        );
    }

    @Test
    @DisplayName("만료된 링크도 Hash에 저장해 이후 요청을 캐시에서 404 처리한다")
    void storesExpiredEntry() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 11, 12, 0);
        RedirectCacheEntry entry = new RedirectCacheEntry(
                1L,
                "https://example.com",
                true,
                expiresAt
        );

        redirectCache.put("demo", entry);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(KEY)),
                eq("1"),
                eq("https://example.com"),
                eq("true"),
                eq(expiresAt.toString()),
                eq("600")
        );
    }

    @Test
    @DisplayName("비활성 링크도 일반 링크와 동일한 TTL로 저장한다")
    void storesInactiveEntryWithDefaultTtl() {
        RedirectCacheEntry entry = new RedirectCacheEntry(1L, "https://example.com", false, null);

        redirectCache.put("demo", entry);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(KEY)),
                eq("1"),
                eq("https://example.com"),
                eq("false"),
                eq(""),
                eq("600")
        );
    }

    @Test
    @DisplayName("Redis 조회 오류는 캐시 미스로 처리한다")
    void returnsEmptyWhenRedisLookupFails() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(KEY)).thenThrow(new RedisConnectionFailureException("Redis unavailable"));

        assertThat(redirectCache.get("demo")).isEmpty();
    }
}
