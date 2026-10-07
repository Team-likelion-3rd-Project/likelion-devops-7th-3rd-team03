package redirect_service.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalSampleDataConfig {

    private static final String SAMPLE_USER_ID = "00000000-0000-0000-0000-000000000001";

    @Bean
    ApplicationRunner localSampleDataInitializer(JdbcTemplate jdbcTemplate) {
        return args -> {
            createSampleUserIfAbsent(jdbcTemplate);
            Long userId = jdbcTemplate.queryForObject(
                    "select id from users where user_id = ?", Long.class, SAMPLE_USER_ID
            );

            insertLinkIfAbsent(jdbcTemplate, userId, "demo", "https://example.com", true, null);
            insertLinkIfAbsent(jdbcTemplate, userId, "hidden", "https://example.com/hidden", false, null);
            insertLinkIfAbsent(
                    jdbcTemplate,
                    userId,
                    "expired",
                    "https://example.com/expired",
                    true,
                    LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
            );
        };
    }

    private void createSampleUserIfAbsent(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "insert into users (user_id, kakao_id, nickname) "
                        + "select ?, ?, ? where not exists (select 1 from users where user_id = ?)",
                SAMPLE_USER_ID, 9_000_000_001L, "로컬 테스트 사용자", SAMPLE_USER_ID
        );
    }

    private void insertLinkIfAbsent(
            JdbcTemplate jdbcTemplate,
            Long userId,
            String slug,
            String originalUrl,
            boolean isVisible,
            LocalDateTime expiresAt
    ) {
        jdbcTemplate.update(
                "insert into links (link_id, user_id, slug, title, original_url, is_visible, expires_at) "
                        + "select ?, ?, ?, ?, ?, ?, ? where not exists (select 1 from links where slug = ?)",
                "local-" + slug,
                userId,
                slug,
                "로컬 예시 링크: " + slug,
                originalUrl,
                isVisible,
                expiresAt,
                slug
        );
    }
}
