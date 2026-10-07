package redirect_service.redirect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import redirect_service.config.RedirectProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class RedisRedirectCache {

    private static final String KEY_PREFIX = "link:";

    private static final String LINK_ID_FIELD = "linkId";
    private static final String ORIGINAL_URL_FIELD = "originalUrl";
    private static final String ENABLED_FIELD = "isEnabled";
    private static final String EXPIRES_AT_FIELD = "expiresAt";

    /**
     * - KEYS[1]: Redis Key (예: "link:abc")
     * - ARGV[1~4]: Hash 필드 값들 (linkId, originalUrl, isEnabled, expiresAt)
     * - ARGV[5]: TTL (초 단위)
     */
    private static final RedisScript<String> PUT_CACHE_SCRIPT = RedisScript.of(
            "redis.call('HSET', KEYS[1], '" + LINK_ID_FIELD + "', ARGV[1], '"
                    + ORIGINAL_URL_FIELD + "', ARGV[2], '"
                    + ENABLED_FIELD + "', ARGV[3], '"
                    + EXPIRES_AT_FIELD + "', ARGV[4]); " +
                    "redis.call('EXPIRE', KEYS[1], ARGV[5]); " +
                    "return 'OK';",
            String.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final RedirectProperties properties;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter cacheErrorCounter;
    private final Timer cacheLookupTimer;

    public RedisRedirectCache(StringRedisTemplate stringRedisTemplate, RedirectProperties properties,
            MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.cacheHitCounter = Counter.builder("redirect_cache_result_total")
                .tag("result", "hit")
                .description("Redirect cache lookup results")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("redirect_cache_result_total")
                .tag("result", "miss")
                .description("Redirect cache lookup results")
                .register(meterRegistry);
        this.cacheErrorCounter = Counter.builder("redirect_cache_result_total")
                .tag("result", "error")
                .description("Redirect cache lookup results")
                .register(meterRegistry);
        this.cacheLookupTimer = Timer.builder("redirect_cache_lookup_duration_seconds")
                .description("Redirect cache lookup latency")
                .register(meterRegistry);
    }

    public Optional<RedirectCacheEntry> get(String slug) {
        Timer.Sample sample = Timer.start();
        try {
            Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(key(slug));

            if (fields.isEmpty()) {
                cacheMissCounter.increment();
                return Optional.empty();
            }

            cacheHitCounter.increment();
            return Optional.of(toEntry(fields));
        } catch (RuntimeException exception) {
            cacheErrorCounter.increment();
            log.warn("캐시 조회 중 예외가 발생하여 DB 조회를 수행합니다. slug={}", slug, exception);
            return Optional.empty();
        } finally {
            sample.stop(cacheLookupTimer);
        }
    }

    public void put(String slug, RedirectCacheEntry entry) {
        try {
            // Lua Script로 HSET+EXPIRE를 한 번의 네트워크 왕복으로 원자적 처리
            stringRedisTemplate.execute(
                    PUT_CACHE_SCRIPT,
                    List.of(key(slug)),                                            // KEYS[1]
                    entry.linkId().toString(),                                      // ARGV[1]
                    entry.originalUrl(),                                            // ARGV[2]
                    Boolean.toString(entry.isEnabled()),                            // ARGV[3]
                    entry.expiresAt() == null ? "" : entry.expiresAt().toString(),  // ARGV[4]
                    String.valueOf(properties.getCacheTtl().getSeconds())          // ARGV[5]
            );
        } catch (RuntimeException exception) {
            log.warn("캐시 저장(put) 중 예외가 발생했으나 캐시 없이 처리를 진행합니다. slug={}", slug, exception);
        }
    }

    private String key(String slug) {
        return KEY_PREFIX + slug;
    }

    private RedirectCacheEntry toEntry(Map<Object, Object> fields) {
        String expiresAt = valueOf(fields, EXPIRES_AT_FIELD);
        return new RedirectCacheEntry(
                Long.parseLong(Objects.requireNonNull(valueOf(fields, LINK_ID_FIELD))),
                valueOf(fields, ORIGINAL_URL_FIELD),
                Boolean.parseBoolean(valueOf(fields, ENABLED_FIELD)),
                expiresAt == null || expiresAt.isBlank() ? null : LocalDateTime.parse(expiresAt)
        );
    }

    private String valueOf(Map<Object, Object> fields, String fieldName) {
        Object value = fields.get(fieldName);
        return value == null ? null : value.toString();
    }
}
