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

            createPersonalWorkspaceIfAbsent(jdbcTemplate, userId);

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
                "insert into users (user_id, provider, social_id, nickname, email) "
                        + "select ?, 'KAKAO', ?, ?, ? where not exists (select 1 from users where user_id = ?)",
                SAMPLE_USER_ID, "9000000001", "로컬 테스트 사용자", "local-sample@example.com", SAMPLE_USER_ID
        );
    }

    /** 링크는 워크스페이스 소유라(links.workspace_id NOT NULL) 가입 때처럼 개인 워크스페이스와 관리자 멤버십을 만든다 */
    private void createPersonalWorkspaceIfAbsent(JdbcTemplate jdbcTemplate, Long userId) {
        jdbcTemplate.update(
                "insert into workspaces (workspace_id, name, is_personal, personal_owner_id, status) "
                        + "select uuid(), '내 워크스페이스', true, ?, 'ACTIVE' "
                        + "where not exists (select 1 from workspaces where personal_owner_id = ?)",
                userId, userId
        );
        jdbcTemplate.update(
                "insert into workspace_members (workspace_id, user_id, role) "
                        + "select w.id, ?, 'ADMIN' from workspaces w where w.personal_owner_id = ? "
                        + "and not exists (select 1 from workspace_members m where m.workspace_id = w.id and m.user_id = ?)",
                userId, userId, userId
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
                "insert into links (link_id, user_id, workspace_id, slug, title, original_url, is_visible, expires_at) "
                        + "select ?, ?, w.id, ?, ?, ?, ?, ? from workspaces w where w.personal_owner_id = ? "
                        + "and not exists (select 1 from links where slug = ?)",
                "local-" + slug,
                userId,
                slug,
                "로컬 예시 링크: " + slug,
                originalUrl,
                isVisible,
                expiresAt,
                userId,
                slug
        );
    }
}
