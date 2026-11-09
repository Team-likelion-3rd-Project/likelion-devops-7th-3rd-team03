package redirect_service.redirect;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedirectServiceSingleFlightTest {

    private final LinkRepository linkRepository = mock(LinkRepository.class);
    private final RedisRedirectCache cache = mock(RedisRedirectCache.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedirectService service =
            new RedirectService(linkRepository, cache, mock(ApplicationEventPublisher.class), registry);

    @Test
    void 같은_slug의_동시_캐시_미스는_DB를_한_번만_조회한다() throws Exception {
        // 캐시를 실제처럼: put 하면 그 뒤 get 에서 나온다
        AtomicReference<RedirectCacheEntry> stored = new AtomicReference<>();
        CountDownLatch allMissed = new CountDownLatch(50);
        when(cache.get("hot")).thenAnswer(inv -> {
            Optional<RedirectCacheEntry> value = Optional.ofNullable(stored.get());
            if (value.isEmpty()) {
                allMissed.countDown();
            }
            return value;
        });
        doAnswer(inv -> { stored.set(inv.getArgument(1)); return null; }).when(cache).put(eq("hot"), any());

        CountDownLatch release = new CountDownLatch(1);
        Link link = mock(Link.class);
        when(link.getId()).thenReturn(1L);
        when(link.getOriginalUrl()).thenReturn("https://example.com");
        when(link.isEnabled()).thenReturn(true);
        when(link.getExpiresAt()).thenReturn(null);
        when(linkRepository.findBySlug("hot")).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);   // DB 가 느린 동안 다른 요청이 몰린다
            return Optional.of(link);
        });

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Future<RedirectTarget>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> service.findRedirectTarget("hot", LocalDateTime.of(2026, 11, 9, 0, 0))));
        }
        assertThat(allMissed.await(5, TimeUnit.SECONDS)).isTrue();   // 50개가 모두 캐시 미스
        Thread.sleep(200);                                           // 미스한 요청들이 진행 중 조회에 합류할 시간
        release.countDown();

        for (Future<RedirectTarget> r : results) {
            assertThat(r.get(5, TimeUnit.SECONDS).originalUrl()).isEqualTo("https://example.com");
        }
        pool.shutdown();

        verify(linkRepository, times(1)).findBySlug("hot");
        assertThat(registry.get("redirect_cache_load_total").tag("result", "db").counter().count()).isEqualTo(1);
        assertThat(registry.get("redirect_cache_load_total").tag("result", "joined").counter().count()).isEqualTo(49);
    }

    @Test
    void 없는_slug는_함께_기다린_요청도_모두_404() throws Exception {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(linkRepository.findBySlug("none")).thenAnswer(inv -> { Thread.sleep(200); return Optional.empty(); });

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> service.findRedirectTarget("none", LocalDateTime.of(2026, 11, 9, 0, 0))));
        }
        for (Future<?> r : results) {
            assertThat(catchCause(r)).isInstanceOf(redirect_service.exception.RedirectNotFoundException.class);
        }
        pool.shutdown();
        verify(linkRepository, atMost(2)).findBySlug("none");   // 거의 동시에 들어오면 1번, 시작 타이밍에 따라 2번
    }

    private static Throwable catchCause(Future<?> f) throws InterruptedException, TimeoutException {
        try {
            f.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}