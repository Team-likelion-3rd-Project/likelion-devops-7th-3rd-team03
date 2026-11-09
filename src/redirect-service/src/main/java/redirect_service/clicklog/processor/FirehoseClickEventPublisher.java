package redirect_service.clicklog.processor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchResponse;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchResponseEntry;
import software.amazon.awssdk.services.firehose.model.Record;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 완성된 클릭 이벤트를 Firehose(Direct PUT)로 묶어서 보낸다 (#263).
 *
 * 예전에는 클릭마다 putRecord 를 한 건씩 동기로 호출해, 처리량이 "스레드 수 ÷ 왕복 시간"에 묶였다.
 * 바이럴 800 TPS 에서 대기열이 차 30.8% 가 로그 없이 버려졌다.
 *
 * 지금은
 * - 클릭은 메모리 큐에 넣기만 한다 (리다이렉트 경로를 막지 않는다)
 * - 전용 전송 스레드가 큐에서 최대 500건(PutRecordBatch 한도)씩 꺼내 한 번에 보낸다
 * - PutRecordBatch 는 일부만 실패할 수 있어 실패한 레코드만 다시 보낸다
 * - 종료할 때 큐를 비우고 끝낸다
 * - 보낸 건수와 버린 건수(큐 가득 참 / 전송 실패)를 지표로 남긴다
 *
 * 전송 실패는 리다이렉트에 영향을 주지 않는다. AWS 환경 전용(app.click-event.sink=firehose, 기본값).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.click-event.sink", havingValue = "firehose", matchIfMissing = true)
public class FirehoseClickEventPublisher implements SmartLifecycle {

    /** PutRecordBatch 한도: 요청당 레코드 500개, 4 MiB */
    static final int MAX_BATCH_RECORDS = 500;
    static final long MAX_BATCH_BYTES = 4L * 1024 * 1024;

    private final FirehoseClient firehoseClient;
    private final JsonMapper jsonMapper;
    private final String streamName;
    private final BlockingQueue<String> queue;
    private final Duration linger;
    private final int maxAttempts;
    private final Duration shutdownTimeout;

    private final Counter sent;
    private final Counter droppedQueueFull;
    private final Counter droppedSendFailed;

    private volatile boolean running;
    private Thread sender;

    public FirehoseClickEventPublisher(
            FirehoseClient firehoseClient,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry,
            @Value("${app.click-event.firehose-stream-name}") String streamName,
            @Value("${app.click-event.firehose.queue-capacity:50000}") int queueCapacity,
            @Value("${app.click-event.firehose.linger:200ms}") String linger,
            @Value("${app.click-event.firehose.max-attempts:3}") int maxAttempts,
            @Value("${app.click-event.firehose.shutdown-timeout:10s}") String shutdownTimeout) {
        this.firehoseClient = firehoseClient;
        this.jsonMapper = jsonMapper;
        this.streamName = streamName;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.linger = DurationStyle.detectAndParse(linger);
        this.maxAttempts = maxAttempts;
        this.shutdownTimeout = DurationStyle.detectAndParse(shutdownTimeout);

        this.sent = counter(meterRegistry, "sent");
        this.droppedQueueFull = counter(meterRegistry, "dropped_queue_full");
        this.droppedSendFailed = counter(meterRegistry, "dropped_send_failed");
        Gauge.builder("click_event_firehose_queue_size", queue, BlockingQueue::size)
                .description("Firehose 로 보내기 전 대기 중인 클릭 수")
                .register(meterRegistry);
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("click_event_firehose_records_total")
                .description("Firehose 로 보낸 클릭 이벤트 수 (result: sent, dropped_queue_full, dropped_send_failed)")
                .tag("result", result)
                .register(registry);
    }

    /** 조립 스레드에서 호출된다. 큐에 넣기만 하고 바로 돌아간다 */
    @EventListener
    public void onClick(ClickEvent clickEvent) {
        String json;
        try {
            json = jsonMapper.writeValueAsString(clickEvent) + "\n";
        } catch (JacksonException e) {
            log.warn("Click event could not be serialized for Firehose. eventId={}", clickEvent.eventId(), e);
            return;
        }
        if (!queue.offer(json)) {
            droppedQueueFull.increment();
        }
    }

    // ---- 전송 스레드 ----

    private void runSender() {
        while (running || !queue.isEmpty()) {
            try {
                List<String> batch = takeBatch();
                if (!batch.isEmpty()) {
                    send(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 전송 스레드가 죽으면 이후 클릭이 전부 큐에서 버려지므로 어떤 오류도 루프를 끝내지 않는다
                log.error("Firehose batch sender loop error", e);
            }
        }
    }

    /** 첫 레코드를 linger 동안 기다리고, 그 뒤 쌓여 있는 것을 한도까지 한 번에 꺼낸다 */
    List<String> takeBatch() throws InterruptedException {
        List<String> batch = new ArrayList<>(MAX_BATCH_RECORDS);
        String first = queue.poll(linger.toMillis(), TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        queue.drainTo(batch, MAX_BATCH_RECORDS - 1);
        return batch;
    }

    void send(List<String> batch) {
        // 레코드는 보통 수백 바이트라 500건이어도 4 MiB 를 넘지 않지만, 넘으면 나눠 보낸다
        List<String> chunk = new ArrayList<>();
        long chunkBytes = 0;
        for (String json : batch) {
            long size = json.getBytes(StandardCharsets.UTF_8).length;
            if (!chunk.isEmpty() && chunkBytes + size > MAX_BATCH_BYTES) {
                sendWithRetry(chunk);
                chunk = new ArrayList<>();
                chunkBytes = 0;
            }
            chunk.add(json);
            chunkBytes += size;
        }
        if (!chunk.isEmpty()) {
            sendWithRetry(chunk);
        }
    }

    private void sendWithRetry(List<String> records) {
        List<String> pending = records;
        for (int attempt = 1; attempt <= maxAttempts && !pending.isEmpty(); attempt++) {
            try {
                PutRecordBatchResponse response = firehoseClient.putRecordBatch(PutRecordBatchRequest.builder()
                        .deliveryStreamName(streamName)
                        .records(pending.stream()
                                .map(json -> Record.builder().data(SdkBytes.fromUtf8String(json)).build())
                                .toList())
                        .build());

                // 실패한 레코드만 골라 다시 보낸다 (응답 순서 = 요청 순서)
                List<String> failed = new ArrayList<>();
                List<PutRecordBatchResponseEntry> entries = response.requestResponses();
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).errorCode() != null) {
                        failed.add(pending.get(i));
                    }
                }
                sent.increment(pending.size() - failed.size());
                if (!failed.isEmpty()) {
                    log.warn("Firehose PutRecordBatch partial failure. attempt={}, failed={}/{}, firstError={}",
                            attempt, failed.size(), pending.size(), firstError(entries));
                }
                pending = failed;
            } catch (RuntimeException e) {
                log.warn("Firehose PutRecordBatch failed. attempt={}, records={}", attempt, pending.size(), e);
            }
            if (!pending.isEmpty() && attempt < maxAttempts) {
                sleepQuietly(100L * attempt);
            }
        }
        if (!pending.isEmpty()) {
            droppedSendFailed.increment(pending.size());
            log.error("Firehose 전송 재시도를 다 써서 클릭 {}건을 버립니다. streamName={}", pending.size(), streamName);
        }
    }

    private static String firstError(List<PutRecordBatchResponseEntry> entries) {
        return entries.stream()
                .filter(e -> e.errorCode() != null)
                .map(e -> e.errorCode() + ": " + e.errorMessage())
                .findFirst()
                .orElse("-");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 수명 주기 ----

    @Override
    public void start() {
        running = true;
        sender = new Thread(this::runSender, "firehose-batch-sender");
        sender.start();
    }

    /**
     * 새로 들어오는 클릭은 받지 않고, 큐에 남은 것을 보낸 뒤 끝낸다.
     * 시간 안에 못 끝내면 전송 스레드를 멈춘 다음 남은 것을 센다.
     * (전송 스레드가 살아 있는 채로 세면, 그 뒤에 보낸 것까지 버림으로 잡혀 지표가 커진다)
     */
    @Override
    public void stop() {
        running = false;
        try {
            sender.join(shutdownTimeout.toMillis());
            if (sender.isAlive()) {
                sender.interrupt();
                sender.join(1_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> left = new ArrayList<>();
        queue.drainTo(left);
        if (!left.isEmpty()) {
            droppedSendFailed.increment(left.size());
            log.error("종료 시간 안에 보내지 못한 클릭 {}건을 버립니다", left.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 종료 순서 (#263 리뷰 반영). SmartLifecycle 은 phase 가 큰 것부터 멈춘다.
     *   웹 서버 graceful shutdown (MAX-1024, 처리 중인 요청 마무리)
     *   → 웹 서버 정지 (MAX-2048)
     *   → 조립 실행기 (MAX-3000) → 로그·실시간 실행기 (MAX-3500)   (AsyncEventConfig)
     *   → 이 전송기 (MAX-4096, 큐를 비우고 전송)
     *   → RealtimeStatsRecorder (1024, Redis flush) → Redis 연결 (0)
     * 앞 단계가 모두 멈춘 뒤에 멈춰야 마지막까지 들어온 클릭을 보낼 수 있다.
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    @Override
    public int getPhase() {
        return PHASE;
    }
}