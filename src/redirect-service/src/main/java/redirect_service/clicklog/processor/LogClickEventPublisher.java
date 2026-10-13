package redirect_service.clicklog.processor;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 온프렘 환경의 클릭 이벤트 발행기. 이벤트를 전용 파일(click-events.log)에 한 줄 JSON으로 기록하고,
 * Fluent Bit이 이 파일을 읽어 S3 호환 저장소(SeaweedFS)에 적재한다.
 *
 * app.click-event.sink=log일 때만 등록된다. AWS(sink=firehose, 기본값)에서는
 * FirehoseClickEventPublisher가 직접 전송하므로 등록되지 않는다.
 * 기존 ClickEventLogger(콘솔 관측용)와는 별개로 동작한다.
 */
@Component
@ConditionalOnProperty(name = "app.click-event.sink", havingValue = "log")
@RequiredArgsConstructor
public class LogClickEventPublisher {

    /** Fluent Bit이 읽는 click-events.log 전용 — 매 줄이 순수 JSON이어야 한다. */
    private static final Logger CLICK_EVENT_FILE_LOG = LoggerFactory.getLogger("click.event.file");

    /** 직렬화 실패 등 운영 경고 — click-events.log를 오염시키지 않도록 별도로 남긴다. */
    private static final Logger OPERATIONAL_LOG = LoggerFactory.getLogger(LogClickEventPublisher.class);

    private final JsonMapper jsonMapper;

    @Async("clickEventLoggerExecutor")
    @EventListener
    public void onClick(ClickEvent clickEvent) {
        try {
            CLICK_EVENT_FILE_LOG.info(jsonMapper.writeValueAsString(clickEvent));
        } catch (JacksonException exception) {
            OPERATIONAL_LOG.warn("Click event could not be serialized", exception);
        }
    }
}