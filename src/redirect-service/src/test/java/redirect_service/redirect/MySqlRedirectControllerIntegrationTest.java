package redirect_service.redirect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redirect_service.redirect.RedisRedirectCache;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 MySQL 8에 src/db의 V*.sql migration을 적용하는 테스트다.
 * Hibernate는 테이블을 생성하지 않고(validate), migration 결과만 사용한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional
class MySqlRedirectControllerIntegrationTest {

    private static final AtomicLong KAKAO_ID_SEQUENCE = new AtomicLong(1);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("shortlink")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private RedisRedirectCache redirectCache;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @BeforeEach
    void setUp() {
        when(redirectCache.get(anyString())).thenReturn(Optional.empty());
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("migration으로 생성된 MySQL 스키마에서 활성 링크는 302로 리다이렉트한다")
    void redirectsForVisibleLinkInMigratedMySqlSchema() throws Exception {
        insertLink("mysql-valid", "https://example.com/mysql", true, null);

        mockMvc.perform(get("/mysql-valid"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/mysql"))
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    @DisplayName("migration으로 생성된 MySQL 스키마에서 비활성 또는 만료 링크는 404를 반환한다")
    void returnsNotFoundForInvisibleOrExpiredLinkInMigratedMySqlSchema() throws Exception {
        insertLink("mysql-hidden", "https://example.com/hidden", false, null);
        insertLink("mysql-expired", "https://example.com/expired", true,
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        mockMvc.perform(get("/mysql-hidden"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/mysql-expired"))
                .andExpect(status().isNotFound());
    }

    private void insertLink(String slug, String originalUrl, boolean isVisible, LocalDateTime expiresAt) {
        String userExternalId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into users (user_id, kakao_id, status) values (?, ?, 'ACTIVE')",
                userExternalId,
                KAKAO_ID_SEQUENCE.getAndIncrement()
        );

        Long userId = jdbcTemplate.queryForObject(
                "select id from users where user_id = ?",
                Long.class,
                userExternalId
        );

        jdbcTemplate.update(
                "insert into links (link_id, user_id, slug, original_url, is_visible, expires_at) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                userId,
                slug,
                originalUrl,
                isVisible,
                expiresAt
        );
    }
}
