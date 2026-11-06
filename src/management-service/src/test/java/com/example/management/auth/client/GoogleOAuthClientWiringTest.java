package com.example.management.auth.client;

import com.example.management.auth.config.GoogleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthClientWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withBean(GoogleProperties.class, () -> new GoogleProperties("id", "secret", "uri"))
            .withUserConfiguration(GoogleOAuthClient.class);

    @Test
    void googleOAuthClientIsRegistered_whenClientIdConfigured() {
        contextRunner
                .withPropertyValues("google.client-id=id")
                .run(context -> assertThat(context).hasSingleBean(GoogleOAuthClient.class));
    }

    @Test
    void googleOAuthClientIsNotRegistered_whenClientIdMissing() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(GoogleOAuthClient.class));
    }
}