package redirect_service.clicklog.processor;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LogClickEventPublisherSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(LogClickEventPublisher.class);

    @Test
    void sink_미지정시_파일_발행기가_뜨지_않는다() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(LogClickEventPublisher.class));
    }

    @Test
    void sink가_log면_파일_발행기가_뜬다() {
        runner.withPropertyValues("app.click-event.sink=log")
                .run(ctx -> assertThat(ctx).hasSingleBean(LogClickEventPublisher.class));
    }
}