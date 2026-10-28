package com.example.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 가 응답하지 않을 때 기본 60초를 기다리지 않도록 타임아웃이 설정돼 있는지 확인한다.
 * 앱 전체(DB·Redis)를 띄우지 않고 application.yml 만 읽어서 본다.
 */
class RedisTimeoutPropertiesTest {

    @Test
    void Redis_명령과_연결_타임아웃이_설정돼_있다() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);

        assertThat(env.getProperty("spring.data.redis.timeout")).isEqualTo("500ms");
        assertThat(env.getProperty("spring.data.redis.connect-timeout")).isEqualTo("2s");
    }
}