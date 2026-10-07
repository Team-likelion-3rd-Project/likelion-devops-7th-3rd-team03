package com.example.management.links.cache;

import com.example.management.links.config.RedirectCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisRedirectCacheWriterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RedisRedirectCacheWriter cacheWriter;

    @BeforeEach
    void setUp() {
        RedirectCacheProperties properties = new RedirectCacheProperties();
        properties.setTtl(Duration.ofMinutes(10));
        cacheWriter = new RedisRedirectCacheWriter(stringRedisTemplate, properties);
    }

    @Test
    @DisplayName("활성 링크는 전체 Hash 필드와 일반 TTL로 원자적으로 갱신한다")
    void writesActiveEntryWithDefaultTtl() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 1, 0, 0);

        cacheWriter.put(new RedirectCacheEntry(7L, "Ab3f2Xz", "https://example.com", true, expiresAt));

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of("link:Ab3f2Xz")),
                eq("7"), eq("https://example.com"), eq("true"), eq(expiresAt.toString()), eq("600")
        );
    }

    @Test
    @DisplayName("삭제된 링크도 키를 지우지 않고 동일한 TTL로 비활성 상태를 갱신한다")
    void writesInactiveEntryWithDefaultTtl() {
        cacheWriter.put(new RedirectCacheEntry(7L, "Ab3f2Xz", "https://example.com", false, null));

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of("link:Ab3f2Xz")),
                eq("7"), eq("https://example.com"), eq("false"), eq(""), eq("600")
        );
    }
}
