package redirect_service.redirect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import redirect_service.config.WarmupProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 트래픽을 받기 전에 자기 자신에게 리다이렉트 요청을 보내 JIT와 커넥션 풀을 데운다 (#264).
 *
 * HPA가 늘린 새 파드는 차가운 JVM이라 처음 1분 남짓 매우 느리다 (로컬 측정: 첫 30초 p99 15.8s → 90초 뒤 2.4ms).
 * Spring Boot는 ApplicationRunner를 모두 실행한 뒤에 readiness를 ACCEPTING_TRAFFIC으로 바꾸므로,
 * 여기서 워밍업하는 동안 readiness는 DOWN이고 Service·ALB가 트래픽을 보내지 않는다.
 *
 * 실제 바이럴 트래픽과 같은 경로(쿠키·Redis 캐시 히트·302)를 타도록, 워밍업 전용 slug를 캐시에 직접 넣는다.
 * DB에는 행을 만들지 않고, 클릭 이벤트는 RedirectService가 발행하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.warmup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedirectWarmup implements ApplicationRunner {

    /** base62 slug에는 '_'가 없어서 실제 링크와 겹치지 않는다 */
    public static final String SLUG = "_warmup";
    private static final RedirectCacheEntry ENTRY = new RedirectCacheEntry(0L, "https://example.com", true, null);

    /** 첫 Redis 연결(ElastiCache TLS)은 차가운 JVM에서 연결 timeout을 넘길 수 있어 재시도한다 */
    private static final int CACHE_PREPARE_ATTEMPTS = 10;
    private static final Duration CACHE_PREPARE_INTERVAL = Duration.ofSeconds(1);

    private final RedisRedirectCache redirectCache;
    private final WarmupProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        // 워밍업이 실패해도 기동은 계속한다. 차가운 상태로 트래픽을 받을 뿐이다
        try {
            warmUp();
        } catch (RuntimeException exception) {
            log.warn("워밍업 중 예외가 발생해 중단하고 트래픽을 받습니다.", exception);
        }
    }

    private void warmUp() {
        // 캐시가 없으면 매 요청이 DB 조회(없는 slug)가 되므로, 끝내 준비하지 못하면 건너뛴다
        if (!prepareCacheEntry()) {
            log.warn("워밍업 캐시를 저장하지 못해 워밍업을 건너뜁니다.");
            return;
        }

        URI uri = URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/" + SLUG);
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();

        int target = properties.getRequests();
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.getMaxDuration().toNanos();
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(properties.getConcurrency());
        try {
            for (int i = 0; i < properties.getConcurrency(); i++) {
                executor.submit(() -> {
                    while (System.nanoTime() < deadline && sent.getAndIncrement() < target) {
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode() != 302) {
                                failed.incrementAndGet();
                            }
                        } catch (IOException exception) {
                            failed.incrementAndGet();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(properties.getMaxDuration().toSeconds() + 5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info("워밍업 완료. requests={}, failed={}, elapsedMs={}",
                Math.min(sent.get(), target), failed.get(), elapsedMs);
    }

    /**
     * 워밍업은 앱에서 처음 Redis를 부르는 호출이라, 연결 수립(ElastiCache는 TLS 핸드셰이크 포함)이 여기에 몰린다.
     * prod에서 첫 연결이 실패(RedisConnectionFailureException)해 워밍업이 건너뛰어진 적이 있어, 연결될 때까지 짧게 재시도한다.
     */
    private boolean prepareCacheEntry() {
        for (int attempt = 1; attempt <= CACHE_PREPARE_ATTEMPTS; attempt++) {
            redirectCache.put(SLUG, ENTRY);
            if (redirectCache.get(SLUG).isPresent()) {
                if (attempt > 1) {
                    log.info("워밍업 캐시 준비 완료. attempt={}", attempt);
                }
                return true;
            }
            try {
                Thread.sleep(CACHE_PREPARE_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}