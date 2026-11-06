package redirect_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.firehose.FirehoseClient;

import java.time.Duration;

/**
 * 자격증명은 SDK 기본 체인이 처리한다 — EKS에서는 IRSA(redirect-service-sa)가 주입하는
 * AWS_ROLE_ARN / AWS_WEB_IDENTITY_TOKEN_FILE 환경변수를 자동으로 읽어간다.
 *
 * 리전은 자격증명과 달리 클라이언트 생성 시점에 즉시 확인되므로, AWS_REGION이 없는
 * 환경(로컬 테스트, CI)에서도 빈 생성 자체가 실패하지 않도록 명시적으로 지정한다.
 * 값은 application.yml(app.click-event.aws-region)에서 관리하며, 코드에는
 * 매직 스트링을 두지 않는다.
 *
 * app.click-event.sink=firehose일 때만 빈을 만든다. 온프렘(sink=log)에는 AWS 자격증명이
 * 없으므로 FirehoseClient를 띄우지 않는다. 값이 없으면(matchIfMissing) firehose로 간주해,
 * 별도 설정이 없는 기존 AWS 배포가 그대로 동작하도록 한다.
 * FirehoseClickEventPublisher와 같은 조건을 써야 한다 — 한쪽만 꺼지면 주입 실패로 기동이 깨진다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.click-event.sink", havingValue = "firehose", matchIfMissing = true)   // 추가
public class FirehoseConfig {

    /**
     * Firehose 호출 타임아웃. 지정하지 않으면 SDK 기본값대로 오래 기다려, Firehose 가 느려지면
     * 배치 전송 스레드(FirehoseClickEventPublisher)가 묶이고 큐가 차서 클릭을 버린다
     * attempt: 한 번 시도의 한도, call: 재시도를 포함한 전체 한도.
     * 값은 문자열("2s")로 받아 직접 Duration 으로 바꾼다. 변환기 없는 환경(설정만 띄우는 테스트)에서도 똑같이 동작하게.
     */
    @Bean
    public FirehoseClient firehoseClient(
            @Value("${app.click-event.aws-region}") String awsRegion,
            @Value("${app.click-event.firehose.attempt-timeout:2s}") String attemptTimeout,
            @Value("${app.click-event.firehose.call-timeout:5s}") String callTimeout) {
        return FirehoseClient.builder()
                .region(Region.of(awsRegion))
                .overrideConfiguration(c -> c
                        .apiCallAttemptTimeout(DurationStyle.detectAndParse(attemptTimeout))
                        .apiCallTimeout(DurationStyle.detectAndParse(callTimeout)))
                .build();
    }
}