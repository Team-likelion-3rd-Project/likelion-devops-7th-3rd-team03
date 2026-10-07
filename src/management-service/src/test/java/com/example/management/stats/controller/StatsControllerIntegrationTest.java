package com.example.management.stats.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatsControllerIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_USER_UUID = "test-user-uuid-" + TEST_USER_ID;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String linkUuid;
    private Long internalLinkId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(TEST_USER_UUID, null, List.of())
        );
        jdbcTemplate.update("""
                INSERT INTO users (id, user_id, kakao_id, nickname, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                ON DUPLICATE KEY UPDATE id = id
                """, TEST_USER_ID, TEST_USER_UUID, 999999L, "테스트유저");

        linkUuid = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO links (link_id, user_id, slug, original_url, is_visible, created_at, updated_at)
                VALUES (?, ?, ?, 'https://example.com', TRUE, NOW(), NOW())
                """, linkUuid, TEST_USER_ID, "test-" + UUID.randomUUID().toString().substring(0, 8));
        internalLinkId = jdbcTemplate.queryForObject(
                "SELECT id FROM links WHERE link_id = ?", Long.class, linkUuid);

        LocalDate today = LocalDate.now(KST);
        redisTemplate.opsForValue().set(clicksKey(today), "17");
        redisTemplate.opsForHyperLogLog().add(uvKey(today), "visitor-1");
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(clicksKey(LocalDate.now(KST)));
        redisTemplate.delete(uvKey(LocalDate.now(KST)));
        jdbcTemplate.update("DELETE FROM links WHERE id = ?", internalLinkId);
        SecurityContextHolder.clearContext();
    }

    @Test
    void 실시간_API는_현재_사용자가_소유한_링크의_클릭과_UV를_반환한다() throws Exception {
        mockMvc.perform(get("/api/links/{linkId}/stats/realtime", linkUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(linkUuid))
                .andExpect(jsonPath("$.realtimeClickCount").value(17))
                .andExpect(jsonPath("$.realtimeUniqueVisitorCount").value(1));
    }

    @Test
    void 존재하지_않는_링크의_실시간_API는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/links/{linkId}/stats/realtime", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    private String clicksKey(LocalDate date) {
        return "stats:%s:link:%d:clicks".formatted(date, internalLinkId);
    }

    private String uvKey(LocalDate date) {
        return "stats:%s:link:%d:uv".formatted(date, internalLinkId);
    }
}
