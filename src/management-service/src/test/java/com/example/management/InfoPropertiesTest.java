package com.example.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/info 에 빌드 커밋이 나오도록 한 설정을 확인한다.
 * 앱 전체(DB·Redis)를 띄우지 않고 application.yml 만 읽어서 본다.
 */
class InfoPropertiesTest {

    @Test
    void info_app_commit_은_APP_COMMIT_값을_쓴다() throws IOException {
        StandardEnvironment env = load(Map.of("APP_COMMIT", "abc1234"));

        assertThat(env.getProperty("info.app.commit")).isEqualTo("abc1234");
        assertThat(env.getProperty("info.app.name")).isEqualTo("management-service");
    }

    @Test
    void APP_COMMIT_이_없으면_unknown_이다() throws IOException {
        StandardEnvironment env = load(Map.of());

        assertThat(env.getProperty("info.app.commit")).isEqualTo("unknown");
    }

    @Test
    void info_엔드포인트가_노출되고_info_설정을_보여준다() throws IOException {
        StandardEnvironment env = load(Map.of());

        assertThat(env.getProperty("management.endpoints.web.exposure.include")).contains("info");
        assertThat(env.getProperty("management.info.env.enabled", Boolean.class)).isTrue();
    }

    private static StandardEnvironment load(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("test", overrides));
        return env;
    }
}