package com.example.management.auth.client;

import com.example.management.auth.config.KakaoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoOAuthClientWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withBean(KakaoProperties.class, () -> new KakaoProperties("id", "secret", "uri"))
            .withUserConfiguration(KakaoOAuthClient.class);

    @Test
    void kakaoOAuthClientWiresWithoutError() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }
}
