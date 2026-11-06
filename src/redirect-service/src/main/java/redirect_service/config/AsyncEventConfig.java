package redirect_service.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class AsyncEventConfig {

    /**
     * 모든 부가 이벤트 처리는 bounded 큐에서 실행하며, 포화되어도 302 응답을 막지 않는다.
     * 넘쳐서 버린 건수는 click_event_dropped_total{stage} 지표로 남긴다 (#263). 예전에는 로그도 없이 버렸다.
     * Firehose 전송은 FirehoseClickEventPublisher 가 자체 큐와 배치 전송으로 처리해 실행기가 없다.
     *
     * 참고: ThreadPoolExecutor 는 큐가 가득 찬 뒤에야 maxPoolSize 까지 늘린다. 큐가 크면 평소엔 core 만 일한다.
     */
    @Bean(name = "clickEventAssemblerExecutor")
    public Executor clickEventAssemblerExecutor(MeterRegistry meterRegistry) {
        return executor("click-assembler-", 2, 4, 10_000, "assembler", meterRegistry);
    }

    @Bean(name = "clickEventLoggerExecutor")
    public Executor clickEventLoggerExecutor(MeterRegistry meterRegistry) {
        return executor("click-event-logger-", 1, 2, 10_000, "logger", meterRegistry);
    }

    @Bean(name = "realtimeStatsRecorderExecutor")
    public Executor realtimeStatsRecorderExecutor(MeterRegistry meterRegistry) {
        return executor("realtime-stats-recorder-", 1, 2, 10_000, "realtime_stats", meterRegistry);
    }

    private Executor executor(String threadNamePrefix, int corePoolSize, int maxPoolSize, int queueCapacity,
                              String stage, MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(countingDiscard(stage, meterRegistry));
        executor.initialize();
        return executor;
    }

    /** DiscardPolicy 와 같이 버리되 건수를 센다 */
    private static RejectedExecutionHandler countingDiscard(String stage, MeterRegistry meterRegistry) {
        Counter dropped = Counter.builder("click_event_dropped_total")
                .description("실행기 큐가 가득 차 버린 클릭 이벤트 작업 수")
                .tag("stage", stage)
                .register(meterRegistry);
        return (task, executor) -> dropped.increment();
    }
}