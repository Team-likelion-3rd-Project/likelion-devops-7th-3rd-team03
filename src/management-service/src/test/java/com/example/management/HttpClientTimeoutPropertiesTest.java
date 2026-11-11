package com.example.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소셜 로그인(카카오·구글·네이버) 호출에 연결·응답 타임아웃이 걸려 있는지 확인한다.
 * 기본 문서(첫 번째)만 읽는다. --- 아래는 batch 프로필 전용이다.
 */
class HttpClientTimeoutPropertiesTest {

    @Test
    void 외부_호출_연결과_응답_타임아웃이_설정돼_있다() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .stream().findFirst()
                .ifPresent(env.getPropertySources()::addLast);

        assertThat(env.getProperty("spring.http.clients.connect-timeout")).isEqualTo("2s");
        assertThat(env.getProperty("spring.http.clients.read-timeout")).isEqualTo("5s");
    }
}