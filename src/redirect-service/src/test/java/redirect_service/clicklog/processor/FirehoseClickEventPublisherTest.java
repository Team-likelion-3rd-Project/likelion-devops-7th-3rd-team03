package redirect_service.clicklog.processor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redirect_service.clicklog.event.ClickEvent;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchResponse;
import software.amazon.awssdk.services.firehose.model.PutRecordBatchResponseEntry;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FirehoseClickEventPublisherTest {

    private final FirehoseClient firehose = mock(FirehoseClient.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private FirehoseClickEventPublisher publisher(int queueCapacity) {
        return new FirehoseClickEventPublisher(firehose, JsonMapper.builder().findAndAddModules().build(), registry,
                "stream", queueCapacity, "10ms", 3, "5s");
    }

    @Test
    void 쌓인_클릭을_최대_500건씩_묶어_보낸다() {
        when(firehose.putRecordBatch(any(PutRecordBatchRequest.class))).thenAnswer(inv -> allSucceeded(inv.getArgument(0)));
        FirehoseClickEventPublisher publisher = publisher(10_000);

        IntStream.range(0, 1_200).forEach(i -> publisher.onClick(event()));   // 시작 전에 쌓아 둠
        publisher.start();
        publisher.stop();   // 큐를 비우고 끝난다

        ArgumentCaptor<PutRecordBatchRequest> captor = ArgumentCaptor.forClass(PutRecordBatchRequest.class);
        verify(firehose, atLeast(3)).putRecordBatch(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.records()).hasSizeLessThanOrEqualTo(500));
        assertThat(captor.getAllValues().stream().mapToInt(r -> r.records().size()).sum()).isEqualTo(1_200);
        assertThat(count("sent")).isEqualTo(1_200);
    }

    @Test
    void 일부만_실패하면_실패한_레코드만_다시_보낸다() {
        when(firehose.putRecordBatch(any(PutRecordBatchRequest.class)))
                .thenAnswer(inv -> firstFailed(inv.getArgument(0)))       // 1번째 레코드만 실패
                .thenAnswer(inv -> allSucceeded(inv.getArgument(0)));
        FirehoseClickEventPublisher publisher = publisher(100);

        IntStream.range(0, 3).forEach(i -> publisher.onClick(event()));
        publisher.start();
        publisher.stop();

        ArgumentCaptor<PutRecordBatchRequest> captor = ArgumentCaptor.forClass(PutRecordBatchRequest.class);
        verify(firehose, times(2)).putRecordBatch(captor.capture());
        assertThat(captor.getAllValues().get(1).records()).hasSize(1);   // 실패한 1건만 재전송
        assertThat(count("sent")).isEqualTo(3);
        assertThat(count("dropped_send_failed")).isZero();
    }

    @Test
    void 재시도를_다_써도_실패하면_버린_건수로_센다() {
        when(firehose.putRecordBatch(any(PutRecordBatchRequest.class))).thenThrow(new RuntimeException("firehose down"));
        FirehoseClickEventPublisher publisher = publisher(100);

        IntStream.range(0, 2).forEach(i -> publisher.onClick(event()));
        publisher.start();
        publisher.stop();

        verify(firehose, times(3)).putRecordBatch(any(PutRecordBatchRequest.class));   // max-attempts 3
        assertThat(count("dropped_send_failed")).isEqualTo(2);
    }

    @Test
    void 큐가_가득_차면_버리고_건수를_센다() {
        FirehoseClickEventPublisher publisher = publisher(2);   // 전송 스레드를 시작하지 않아 큐가 비지 않음

        IntStream.range(0, 5).forEach(i -> publisher.onClick(event()));

        assertThat(count("dropped_queue_full")).isEqualTo(3);
    }

    // ---- 헬퍼 ----

    private double count(String result) {
        return registry.get("click_event_firehose_records_total").tag("result", result).counter().count();
    }

    private static PutRecordBatchResponse allSucceeded(PutRecordBatchRequest request) {
        return PutRecordBatchResponse.builder()
                .failedPutCount(0)
                .requestResponses(request.records().stream()
                        .map(r -> PutRecordBatchResponseEntry.builder().recordId("ok").build())
                        .toList())
                .build();
    }

    private static PutRecordBatchResponse firstFailed(PutRecordBatchRequest request) {
        List<PutRecordBatchResponseEntry> entries = IntStream.range(0, request.records().size())
                .mapToObj(i -> i == 0
                        ? PutRecordBatchResponseEntry.builder().errorCode("ServiceUnavailableException").errorMessage("slow down").build()
                        : PutRecordBatchResponseEntry.builder().recordId("ok").build())
                .toList();
        return PutRecordBatchResponse.builder().failedPutCount(1).requestResponses(entries).build();
    }

    private static ClickEvent event() {
        return new ClickEvent(UUID.randomUUID(), 2, 1L, LocalDateTime.now(), "v", "ko-KR", "ua",
                "DESKTOP", "Windows", "Chrome", null, "DIRECT", false, "KR", null);
    }
}