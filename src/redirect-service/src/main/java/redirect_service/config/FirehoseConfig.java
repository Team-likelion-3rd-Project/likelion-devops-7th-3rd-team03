package redirect_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.firehose.FirehoseClient;

/**
 * 자격증명은 SDK 기본 체인이 처리한다 — EKS에서는 IRSA(redirect-service-sa)가 주입하는
 * AWS_ROLE_ARN / AWS_WEB_IDENTITY_TOKEN_FILE 환경변수를 자동으로 읽어간다.
 *
 * 리전은 자격증명과 달리 클라이언트 생성 시점에 즉시 확인되므로, AWS_REGION이 없는
 * 환경(로컬 테스트, CI)에서도 빈 생성 자체가 실패하지 않도록 명시적으로 지정한다.
 * 값은 application.yml(app.click-event.aws-region)에서 관리하며, 코드에는
 * 매직 스트링을 두지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class FirehoseConfig {

    @Bean
    public FirehoseClient firehoseClient(@Value("${app.click-event.aws-region}") String awsRegion) {
        return FirehoseClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}