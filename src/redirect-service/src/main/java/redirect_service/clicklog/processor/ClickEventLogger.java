package redirect_service.clicklog.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import redirect_service.clicklog.event.ClickEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 개발·운영 관측용 구조화 로그의 최종 소비자다. */
@Slf4j(topic = "click.event")
@Component
@RequiredArgsConstructor
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
