package com.example.management.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.athena.AthenaClient;

/**
 * batch 프로필로 실행될 때만 Athena 클라이언트를 띄운다.
 * 자격증명은 SDK 기본 체인이 처리한다 — EKS에서는 IRSA(athena-batch-sa 서비스어카운트)가
 * 주입하는 AWS_ROLE_ARN / AWS_WEB_IDENTITY_TOKEN_FILE 환경변수를 자동으로 읽어간다.
 */
@Configuration
@Profile("batch")
public class BatchAwsConfig {

    @Bean
    public AthenaClient athenaClient() {
        return AthenaClient.builder().build();
    }
}
