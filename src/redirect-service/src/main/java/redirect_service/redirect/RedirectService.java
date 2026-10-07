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

@Slf4j
@Service
public class RedirectService {

    private final LinkRepository linkRepository;
    private final RedisRedirectCache redirectCache;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter successOutcomeCounter;
    private final Map<RedirectNotFoundException.Reason, Counter> failureOutcomeCounters;

    public RedirectService(LinkRepository linkRepository, RedisRedirectCache redirectCache,
            ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.linkRepository = linkRepository;
        this.redirectCache = redirectCache;
        this.eventPublisher = eventPublisher;
        this.successOutcomeCounter = outcomeCounter(meterRegistry, "SUCCESS");
        this.failureOutcomeCounters = new EnumMap<>(RedirectNotFoundException.Reason.class);
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
        Link link = linkRepository.findBySlug(slug)
                .orElseThrow(() -> new RedirectNotFoundException(RedirectNotFoundException.Reason.NOT_FOUND));

        // 만료/비활성 링크도 캐시에 저장해 다음 요청을 DB 없이 404로 처리한다.
        RedirectCacheEntry cacheEntry = new RedirectCacheEntry(
                link.getId(),
                link.getOriginalUrl(),
                link.isEnabled(),
                link.getExpiresAt()
        );
        redirectCache.put(slug, cacheEntry);

        if (!link.isRedirectable(now)) {
            throw new RedirectNotFoundException(unavailableReason(link.isEnabled()));
        }
        return cacheEntry.toRedirectTarget();
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
