package redirect_service.clicklog.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** prod 에서는 클릭 콘솔 로그를 끈다 (#274). 꺼지면 빈 자체가 없어 직렬화도 하지 않는다 */
class ClickEventLoggerToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JsonMapper.class, JsonMapper::new)
            .withUserConfiguration(ClickEventLogger.class);

    @Test
    void 설정이_없으면_켜진다() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ClickEventLogger.class));
    }

    @Test
    void false면_빈이_없다() {
        runner.withPropertyValues("app.click-event.console-log.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClickEventLogger.class));
    }
}