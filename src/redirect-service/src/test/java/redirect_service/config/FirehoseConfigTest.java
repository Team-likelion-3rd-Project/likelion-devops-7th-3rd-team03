package redirect_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.ConversionService;
import software.amazon.awssdk.services.firehose.FirehoseClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Firehose 클라이언트에 시도·전체 타임아웃이 들어가는지 확인한다. AWS 에 접속하지 않는다. */
class FirehoseConfigTest {

    @Test
    void 클라이언트에_타임아웃이_설정된다() {
        try (FirehoseClient client = new FirehoseConfig()
                .firehoseClient("ap-southeast-1", Duration.ofSeconds(2), Duration.ofSeconds(5))) {

            var override = client.serviceClientConfiguration().overrideConfiguration();
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
        }
    }

    @Test
    void 설정값_2s_5s_로_클라이언트가_만들어진다() {
        // 실행 중인 앱과 같은 변환기(ApplicationConversionService)로 "2s" → Duration 을 거친다
        new ApplicationContextRunner()
                .withBean("conversionService", ConversionService.class, ApplicationConversionService::getSharedInstance)
                .withUserConfiguration(FirehoseConfig.class)
                .withPropertyValues(
                        "app.click-event.sink=firehose",
                        "app.click-event.aws-region=ap-southeast-1",
                        "app.click-event.firehose.attempt-timeout=2s",
                        "app.click-event.firehose.call-timeout=5s")
                .run(ctx -> {
                    var override = ctx.getBean(FirehoseClient.class).serviceClientConfiguration().overrideConfiguration();
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
                });
    }
}