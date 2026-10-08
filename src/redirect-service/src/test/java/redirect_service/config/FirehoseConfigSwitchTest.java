package redirect_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.firehose.FirehoseClient;

import static org.assertj.core.api.Assertions.assertThat;

class FirehoseConfigSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FirehoseConfig.class)
            .withPropertyValues("app.click-event.aws-region=ap-southeast-1");

    @Test
    void sink_미지정시_기존처럼_Firehose_빈이_뜬다() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(FirehoseClient.class));
    }

    @Test
    void sink가_firehose면_Firehose_빈이_뜬다() {
        runner.withPropertyValues("app.click-event.sink=firehose")
                .run(ctx -> assertThat(ctx).hasSingleBean(FirehoseClient.class));
    }

    @Test
    void sink가_log면_Firehose_빈이_뜨지_않는다() {
        runner.withPropertyValues("app.click-event.sink=log")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FirehoseClient.class));
    }
}