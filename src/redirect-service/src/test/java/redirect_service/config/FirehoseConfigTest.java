package redirect_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.firehose.FirehoseClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Firehose 클라이언트에 시도·전체 타임아웃이 들어가는지 확인한다. AWS 에 접속하지 않는다. */
class FirehoseConfigTest {

    @Test
    void 클라이언트에_타임아웃이_설정된다() {
        try (FirehoseClient client = new FirehoseConfig()
                .firehoseClient("ap-southeast-1", "2s", "5s")) {

            var override = client.serviceClientConfiguration().overrideConfiguration();
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
        }
    }

    @Test
    void 설정값이_없으면_기본_2s_5s_로_만들어진다() {
        // 변환기 없이 설정만 띄워도(FirehoseConfigSwitchTest 와 같은 조건) 기본값이 들어가야 한다
        new ApplicationContextRunner()
                .withUserConfiguration(FirehoseConfig.class)
                .withPropertyValues("app.click-event.aws-region=ap-southeast-1")
                .run(ctx -> {
                    var override = ctx.getBean(FirehoseClient.class).serviceClientConfiguration().overrideConfiguration();
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
                });
    }
}