package com.example.management.links.controller;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.domain.User;
import com.example.management.auth.jwt.JwtTokenProvider;
import com.example.management.auth.service.UserRegistrar;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 링크 API의 워크스페이스 귀속(#204)을 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * 같은 워크스페이스 멤버는 서로의 링크를 보고 고칠 수 있고, 다른 워크스페이스의 링크는 없는 것처럼 다룬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkIntegrationTest {

    /** 테스트가 만든 회원만 지우기 위한 social_id 접두사 */
    private static final String SOCIAL_ID_PREFIX = "it-link-";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRegistrar userRegistrar;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void tearDown() {
        String testUsers = "SELECT id FROM users WHERE social_id LIKE ?";
        List<Long> workspaceIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT workspace_id FROM workspace_members WHERE user_id IN (" + testUsers + ")",
                Long.class, SOCIAL_ID_PREFIX + "%");
        for (Long workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM links WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE social_id LIKE ?", SOCIAL_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("만든 링크는 그 워크스페이스 소유이고, 같은 워크스페이스 멤버의 목록에 만든 사람과 함께 보인다")
    void create_linkBelongsToWorkspaceAndListedForTeammates() throws Exception {
        User admin = newUser("관리자");
        User member = newUser("멤버");
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        addMember(workspaceId, member);

        String linkId = createdLinkId(tokenOf(admin), workspaceId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT w.workspace_id FROM links l JOIN workspaces w ON w.id = l.workspace_id WHERE l.link_id = ?
                """, String.class, linkId)).isEqualTo(workspaceId);
        listLinks(tokenOf(member), workspaceId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.links.length()").value(1))
                .andExpect(jsonPath("$.data.links[0].linkId").value(linkId))
                .andExpect(jsonPath("$.data.links[0].createdBy.userId").value(admin.getUserId()))
                .andExpect(jsonPath("$.data.links[0].createdBy.nickname").value("관리자"));
    }

    @Test
    @DisplayName("같은 워크스페이스 멤버는 다른 사람이 만든 링크를 수정·삭제할 수 있다")
    void updateAndDelete_teammateCanChangeOthersLink() throws Exception {
        User admin = newUser("관리자");
        User member = newUser("멤버");
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        addMember(workspaceId, member);
        String linkId = createdLinkId(tokenOf(admin), workspaceId);

        updateLink(tokenOf(member), workspaceId, linkId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("바뀐 제목"));
        deleteLink(tokenOf(member), workspaceId, linkId).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_visible FROM links WHERE link_id = ?", Boolean.class, linkId)).isFalse();
    }

    @Test
    @DisplayName("워크스페이스 멤버가 아니면 목록·생성·수정·삭제 모두 워크스페이스가 없는 것처럼 404다")
    void outsider_getsWorkspaceNotFound() throws Exception {
        String adminToken = tokenOf(newUser("관리자"));
        String outsiderToken = tokenOf(newUser("외부인"));
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        String linkId = createdLinkId(adminToken, workspaceId);

        listLinks(outsiderToken, workspaceId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_NOT_FOUND"));
        createLink(outsiderToken, workspaceId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_NOT_FOUND"));
        updateLink(outsiderToken, workspaceId, linkId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_NOT_FOUND"));
        deleteLink(outsiderToken, workspaceId, linkId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("내 워크스페이스 경로로 다른 워크스페이스의 링크를 수정·삭제하면 링크가 없는 것처럼 404다")
    void otherWorkspaceLink_getsLinkNotFound() throws Exception {
        String ownerToken = tokenOf(newUser("주인"));
        String otherToken = tokenOf(newUser("다른 팀"));
        String linkId = createdLinkId(ownerToken, createdWorkspaceId(ownerToken, "팀"));
        String otherWorkspaceId = createdWorkspaceId(otherToken, "다른 팀");

        updateLink(otherToken, otherWorkspaceId, linkId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("LINK_NOT_FOUND"));
        deleteLink(otherToken, otherWorkspaceId, linkId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("LINK_NOT_FOUND"));
    }

    private User newUser(String nickname) {
        return userRegistrar.register(User.builder()
                .provider(SocialProvider.KAKAO)
                .socialId(SOCIAL_ID_PREFIX + UUID.randomUUID())
                .nickname(nickname)
                .email("link@test.com")
                .build());
    }

    private String tokenOf(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getUserId());
    }

    private String createdWorkspaceId(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.workspaceId");
    }

    private void addMember(String workspaceId, User user) {
        jdbcTemplate.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role)
                SELECT id, ?, 'MEMBER' FROM workspaces WHERE workspace_id = ?
                """, user.getId(), workspaceId);
    }

    private ResultActions createLink(String token, String workspaceId) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{workspaceId}/links", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"originalUrl":"https://example.com","title":"팀 링크","expiresAt":"%s"}
                        """.formatted(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0))));
    }

    private String createdLinkId(String token, String workspaceId) throws Exception {
        String body = createLink(token, workspaceId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.linkId");
    }

    private ResultActions listLinks(String token, String workspaceId) throws Exception {
        return mockMvc.perform(get("/api/workspaces/{workspaceId}/links", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions updateLink(String token, String workspaceId, String linkId) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/{workspaceId}/links/{linkId}", workspaceId, linkId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"바뀐 제목"}
                        """));
    }

    private ResultActions deleteLink(String token, String workspaceId, String linkId) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/{workspaceId}/links/{linkId}", workspaceId, linkId)
                .header(HttpHeaders.AUTHORIZATION, token));
    }
}
