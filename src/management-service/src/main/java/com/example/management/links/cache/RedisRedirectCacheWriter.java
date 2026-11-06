package com.example.management.links.cache;

import com.example.management.links.config.RedirectCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Redis Hash의 쓰기 측 구현. redirect-service의 RedisRedirectCache와 같은 계약을 사용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRedirectCacheWriter {

    private static final String KEY_PREFIX = "link:";
    private static final String LINK_ID_FIELD = "linkId";
    private static final String ORIGINAL_URL_FIELD = "originalUrl";
    private static final String ENABLED_FIELD = "isEnabled";
    private static final String EXPIRES_AT_FIELD = "expiresAt";

    private static final RedisScript<String> PUT_CACHE_SCRIPT = RedisScript.of(
            "redis.call('HSET', KEYS[1], '" + LINK_ID_FIELD + "', ARGV[1], '"
                    + ORIGINAL_URL_FIELD + "', ARGV[2], '"
                    + ENABLED_FIELD + "', ARGV[3], '"
                    + EXPIRES_AT_FIELD + "', ARGV[4]); "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[5]); "
                    + "return 'OK';",
            String.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final RedirectCacheProperties properties;

    public void put(RedirectCacheEntry entry) {
        try {
            // HSET과 EXPIRE를 분리하면 부분 갱신 또는 TTL 유실 구간이 생길 수 있어 Lua로 묶는다.
            stringRedisTemplate.execute(
                    PUT_CACHE_SCRIPT,
                    List.of(key(entry.slug())),
                    entry.linkId().toString(),
                    entry.originalUrl(),
                    Boolean.toString(entry.isEnabled()),
                    entry.expiresAt() == null ? "" : entry.expiresAt().toString(),
                    String.valueOf(properties.getTtl().getSeconds())
            );
        } catch (Exception exception) {
            // Redis 장애가 링크 관리 DB 트랜잭션을 되돌리지는 않는다. 이후 redirect-service의 miss가 DB에서 복구한다.
            log.warn("리다이렉트 캐시 갱신에 실패했습니다. slug={}", entry.slug(), exception);
        }
    }

    private String key(String slug) {
        return KEY_PREFIX + slug;
    }
}
