package redirect_service.clicklog.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 개발·운영 관측용 구조화 로그의 최종 소비자다.
 * 클릭마다 JSON 을 한 번 더 만들어 콘솔에 찍으므로, 클릭을 Firehose 로 따로 보내는 prod 에서는 끈다 (#274).
 * app.click-event.console-log.enabled=false 면 빈을 만들지 않아 직렬화·실행기 작업 자체가 일어나지 않는다.
 */
@Slf4j(topic = "click.event")
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.click-event.console-log.enabled", havingValue = "true", matchIfMissing = true)
public class ClickEventLogger {

    private final JsonMapper jsonMapper;

    @Async("clickEventLoggerExecutor")
    @EventListener
    public void onClick(ClickEvent clickEvent) {
        try {
            log.info("click_event={}", jsonMapper.writeValueAsString(clickEvent));
        } catch (JacksonException exception) {
            log.warn("Click event could not be serialized", exception);
        }
    }
}
