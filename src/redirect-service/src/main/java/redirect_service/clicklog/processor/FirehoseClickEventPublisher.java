package redirect_service.clicklog.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 완성된 클릭 이벤트를 Firehose(Direct PUT)로 비동기 전송해 S3에 적재한다.
 * 리다이렉트 응답 경로와 완전히 분리돼 있고(별도 스레드풀 + bounded 큐),
 * 전송 실패는 로깅만 하고 삼킨다 — 리다이렉트 자체는 절대 실패하면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FirehoseClickEventPublisher {

    private final FirehoseClient firehoseClient;
    private final JsonMapper jsonMapper;

    @Value("${app.click-event.firehose-stream-name}")
    private String streamName;

    @Async("firehoseClickEventPublisherExecutor")
    @EventListener
    public void onClick(ClickEvent clickEvent) {
        try {
            String json = jsonMapper.writeValueAsString(clickEvent) + "\n";
            firehoseClient.putRecord(PutRecordRequest.builder()
                    .deliveryStreamName(streamName)
                    .record(Record.builder()
                            .data(SdkBytes.fromUtf8String(json))
                            .build())
                    .build());
        } catch (JacksonException e) {
            log.warn("Click event could not be serialized for Firehose. eventId={}",
                    clickEvent.eventId(), e);
        } catch (Exception e) {
            // Firehose 전송 실패는 리다이렉트 응답에 영향을 주지 않는다. 재시도는 하지 않고
            // 로깅만 한다 — 유실된 이벤트는 배치 재집계 시 Athena 소스 데이터에서 빠질 뿐,
            // 실시간 통계(Redis)는 이 경로와 무관하게 이미 반영되어 있다.
            log.warn("Failed to send click event to Firehose. eventId={}, streamName={}",
                    clickEvent.eventId(), streamName, e);
        }
    }
}
