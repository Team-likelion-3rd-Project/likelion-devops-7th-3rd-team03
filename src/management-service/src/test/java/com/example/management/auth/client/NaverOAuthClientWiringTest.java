package com.example.management.auth.client;

import com.example.management.auth.config.NaverProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NaverOAuthClientWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withBean(NaverProperties.class, () -> new NaverProperties("id", "secret"))
            .withUserConfiguration(NaverOAuthClient.class);

    @Test
    void naverOAuthClientIsRegistered_whenClientIdConfigured() {
        contextRunner
                .withPropertyValues("naver.client-id=id")
                .run(context -> assertThat(context).hasSingleBean(NaverOAuthClient.class));
    }

    @Test
    void naverOAuthClientIsNotRegistered_whenClientIdMissing() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(NaverOAuthClient.class));
    }
}
