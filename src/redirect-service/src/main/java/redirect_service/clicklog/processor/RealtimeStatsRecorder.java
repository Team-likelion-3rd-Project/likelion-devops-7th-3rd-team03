package redirect_service.clicklog.processor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import redirect_service.config.RealtimeStatsRecorderProperties;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 당일 잠정 통계를 위한 최종 소비자다. 이벤트는 최대 5초간 bounded buffer에 모은 뒤
 * 하나의 Redis pipeline으로 전송한다. 버퍼 포화나 Redis 오류로 누락된 값은 원본 클릭
 * 이벤트 기반 배치 집계로 확정한다.
 */
@Slf4j
@Component
public class RealtimeStatsRecorder {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // Redis 키 형식: stats:{KST 날짜}:link:{내부 linkId}:{clicks|uv}
    private static final String CLICKS_KEY_PATTERN = "stats:%s:link:%d:clicks";
    private static final String UNIQUE_VISITORS_KEY_PATTERN = "stats:%s:link:%d:uv";

    private static final RedisScript<Long> UPDATE_STATS_SCRIPT = RedisScript.of("""
            redis.call('INCR', KEYS[1])
            redis.call('PFADD', KEYS[2], ARGV[1])
            local expiresAt = tonumber(ARGV[2])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIREAT', KEYS[1], expiresAt) end
            if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIREAT', KEYS[2], expiresAt) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RealtimeStatsRecorderProperties properties;
    private final BlockingQueue<ClickEvent> pendingEvents;
    private final AtomicLong droppedEventCount = new AtomicLong();
    private final Counter flushSuccessCounter;
    private final Counter flushFailureCounter;

    public RealtimeStatsRecorder(StringRedisTemplate redisTemplate, RealtimeStatsRecorderProperties properties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.pendingEvents = new ArrayBlockingQueue<>(properties.getBufferCapacity());
        this.flushSuccessCounter = Counter.builder("redirect_click_stats_flush_total")
                .tag("result", "success")
                .description("Realtime stats Redis pipeline flush outcomes")
                .register(meterRegistry);
        this.flushFailureCounter = Counter.builder("redirect_click_stats_flush_total")
                .tag("result", "failure")
                .description("Realtime stats Redis pipeline flush outcomes")
                .register(meterRegistry);
    }

    @Async("realtimeStatsRecorderExecutor")
    @EventListener
    public void onClick(ClickEvent clickEvent) {
        if (clickEvent.isBot()) {
            return; // Athena 배치(is_bot=false 필터)와 집계 기준을 맞춘다.
        }
        if (!pendingEvents.offer(clickEvent)) {
            long dropped = droppedEventCount.incrementAndGet();
            if (dropped == 1 || dropped % 1_000 == 0) {
                log.warn("실시간 통계 버퍼가 가득 차 클릭 이벤트가 드롭됐습니다. droppedEvents={}", dropped);
            }
        }
    }

    @Scheduled(fixedRateString = "${app.realtime-stats.flush-interval:5s}")
    public void flush() {
        List<ClickEvent> batch = drain();
        if (batch.isEmpty()) {
            return;
        }

        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                    RedisOperations<String, String> stringOperations =
                            (RedisOperations<String, String>) operations;
                    for (ClickEvent event : batch) {
                        LocalDate date = event.clickedAt().atOffset(ZoneOffset.UTC)
                                .atZoneSameInstant(KST).toLocalDate();
                        stringOperations.execute(
                                UPDATE_STATS_SCRIPT,
                                List.of(clicksKey(date, event.linkId()), uniqueVisitorsKey(date, event.linkId())),
                                event.visitorId(),
                                Long.toString(expireAt(date))
                        );
                    }
                    return null;
                }
            });
            flushSuccessCounter.increment();
        } catch (RuntimeException exception) {
            // 파이프라인 실패 뒤 일부 명령 반영 여부는 알 수 없다. 재시도하면 클릭 수를 과대 계상한다.
            flushFailureCounter.increment();
            log.warn("실시간 통계 Redis 배치 반영에 실패해 드롭됐습니다. batchSize={}", batch.size(), exception);
        }
    }

    @PreDestroy
    void flushBeforeShutdown() {
        flush();
    }

    static String clicksKey(LocalDate date, long linkId) {
        return CLICKS_KEY_PATTERN.formatted(date, linkId);
    }

    static String uniqueVisitorsKey(LocalDate date, long linkId) {
        return UNIQUE_VISITORS_KEY_PATTERN.formatted(date, linkId);
    }

    long droppedEventCount() {
        return droppedEventCount.get();
    }

    private long expireAt(LocalDate date) {
        return date.atStartOfDay(KST).toInstant().plus(properties.getTtl()).getEpochSecond();
    }

    private List<ClickEvent> drain() {
        List<ClickEvent> batch = new ArrayList<>();
        pendingEvents.drainTo(batch);
        return batch;
    }
}
