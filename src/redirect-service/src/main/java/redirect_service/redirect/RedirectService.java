package redirect_service.redirect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import redirect_service.clicklog.event.ClickRequestSnapshot;
import redirect_service.clicklog.event.RedirectSucceededEvent;
import redirect_service.exception.RedirectNotFoundException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class RedirectService {

    private final LinkRepository linkRepository;
    private final RedisRedirectCache redirectCache;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter successOutcomeCounter;
    private final Map<RedirectNotFoundException.Reason, Counter> failureOutcomeCounters;
    /**
     * 같은 slug 의 동시 캐시 미스를 한 번의 DB 조회로 합친다 (single-flight, #번호).
     * 캐시가 빈 링크에 요청이 몰리면 요청마다 DB 를 조회해 커넥션 풀이 꽉 찼다 (400건/초에서 대기 71, 최대 7초).
     * 파드 안에서만 합쳐서 파드 수만큼은 DB 조회가 생긴다.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Optional<RedirectCacheEntry>>> inFlightLoads = new ConcurrentHashMap<>();
    private static final long JOIN_TIMEOUT_MILLIS = 2_000;
    private final Counter dbLoadCounter;
    private final Counter joinedLoadCounter;

    public RedirectService(LinkRepository linkRepository, RedisRedirectCache redirectCache,
            ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.linkRepository = linkRepository;
        this.redirectCache = redirectCache;
        this.eventPublisher = eventPublisher;
        this.successOutcomeCounter = outcomeCounter(meterRegistry, "SUCCESS");
        this.failureOutcomeCounters = new EnumMap<>(RedirectNotFoundException.Reason.class);
        this.dbLoadCounter = Counter.builder("redirect_cache_load_total").tag("result", "db")
                .description("캐시 미스로 DB 를 조회한 수 (같은 slug 동시 미스는 한 번)").register(meterRegistry);
        this.joinedLoadCounter = Counter.builder("redirect_cache_load_total").tag("result", "joined")
                .description("다른 요청의 DB 조회 결과를 기다려 받은 수").register(meterRegistry);
        for (RedirectNotFoundException.Reason reason : RedirectNotFoundException.Reason.values()) {
            failureOutcomeCounters.put(reason, outcomeCounter(meterRegistry, reason.name()));
        }
    }

    /**
     * 리다이렉트가 가능한 경우에만 방문자 식별을 확정하고 원본 클릭 이벤트를 발행한다.
     * 이벤트 소비자의 포화·장애는 302 응답을 막지 않는다.
     */
    public RedirectTarget redirect(RedirectRequest request) {
        RedirectTarget target = findRedirectTarget(request.slug(), request.occurredAt());
        // 워밍업 요청은 클릭이 아니다. 통계·Firehose에 넣지 않는다 (#264)
        if (RedirectWarmup.SLUG.equals(request.slug())) {
            return target;
        }
        ClickRequestSnapshot snapshot = ClickRequestSnapshot.from(request);

        try {
            eventPublisher.publishEvent(new RedirectSucceededEvent(target.linkId(), snapshot));
        } catch (RuntimeException exception) {
            log.warn("클릭 이벤트 발행 중 예외가 발생했으나 리다이렉트는 계속 진행합니다. linkId={}", target.linkId(), exception);
        }
        return target;
    }

    public RedirectTarget findRedirectTarget(String slug) {
        return findRedirectTarget(slug, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 캐시를 우선 조회하고, 캐시 미스일 때만 DB를 조회한다(cache-aside).
     */
    public RedirectTarget findRedirectTarget(String slug, LocalDateTime utcNow) {
        try {
            RedirectTarget target = findCachedRedirectTarget(slug, utcNow)
                    .orElseGet(() -> findDatabaseRedirectTarget(slug, utcNow));
            successOutcomeCounter.increment();
            return target;
        } catch (RedirectNotFoundException exception) {
            failureOutcomeCounters.get(exception.reason()).increment();
            throw exception;
        }
    }

    private Optional<RedirectTarget> findCachedRedirectTarget(String slug, LocalDateTime now) {
        Optional<RedirectCacheEntry> cachedEntry = redirectCache.get(slug);
        if (cachedEntry.isEmpty()) {
            log.debug("리다이렉트 캐시 미스. slug={}", slug);
            return Optional.empty();
        }

        log.debug("리다이렉트 캐시 히트. slug={}", slug);
        RedirectCacheEntry entry = cachedEntry.get();
        if (!entry.isRedirectable(now)) {
            throw new RedirectNotFoundException(unavailableReason(entry.isEnabled()));
        }
        return Optional.of(entry.toRedirectTarget());
    }

    private RedirectTarget findDatabaseRedirectTarget(String slug, LocalDateTime now) {
        RedirectCacheEntry entry = loadOnce(slug)
                .orElseThrow(() -> new RedirectNotFoundException(RedirectNotFoundException.Reason.NOT_FOUND));
        if (!entry.isRedirectable(now)) {
            throw new RedirectNotFoundException(unavailableReason(entry.isEnabled()));
        }
        return entry.toRedirectTarget();
    }

    /** 같은 slug 를 이미 조회 중인 요청이 있으면 그 결과를 기다리고, 없으면 직접 조회해 캐시에 넣는다 */
    private Optional<RedirectCacheEntry> loadOnce(String slug) {
        CompletableFuture<Optional<RedirectCacheEntry>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<RedirectCacheEntry>> existing = inFlightLoads.putIfAbsent(slug, mine);
        if (existing != null) {
            try {
                Optional<RedirectCacheEntry> joined = existing.get(JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                joinedLoadCounter.increment();
                return joined;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return loadFromDatabase(slug);
            } catch (ExecutionException | TimeoutException e) {
                // 앞선 조회가 실패하거나 너무 오래 걸리면 각자 조회한다 (기존 동작)
                return loadFromDatabase(slug);
            }
        }
        try {
            Optional<RedirectCacheEntry> loaded = loadFromDatabase(slug);
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlightLoads.remove(slug, mine);
        }
    }

    /** DB 를 조회하고, 만료·비활성 링크도 캐시에 넣어 다음 요청을 DB 없이 404 로 처리한다 */
    private Optional<RedirectCacheEntry> loadFromDatabase(String slug) {
        dbLoadCounter.increment();
        return linkRepository.findBySlug(slug).map(link -> {
            RedirectCacheEntry cacheEntry = new RedirectCacheEntry(
                    link.getId(), link.getOriginalUrl(), link.isEnabled(), link.getExpiresAt());
            redirectCache.put(slug, cacheEntry);
            return cacheEntry;
        });
    }

    /**
     * isRedirectable()이 false인 이유는 비활성화 또는 만료 둘 중 하나뿐이다.
     */
    private RedirectNotFoundException.Reason unavailableReason(boolean enabled) {
        return enabled ? RedirectNotFoundException.Reason.EXPIRED : RedirectNotFoundException.Reason.DISABLED;
    }

    private static Counter outcomeCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("redirect_outcome_total")
                .tag("outcome", outcome)
                .description("Final outcome of a redirect request")
                .register(meterRegistry);
    }
}
