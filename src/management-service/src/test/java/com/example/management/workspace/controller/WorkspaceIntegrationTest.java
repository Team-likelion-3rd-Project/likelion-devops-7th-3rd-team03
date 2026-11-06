package com.example.management.workspace.controller;

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
 * 워크스페이스 API를 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * 사용자는 가입과 같은 경로(UserRegistrar)로 만들어 개인 워크스페이스가 함께 생기게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceIntegrationTest {

    /** 테스트가 만든 회원만 지우기 위한 social_id 접두사 */
    private static final String SOCIAL_ID_PREFIX = "it-ws-";

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
    @DisplayName("가입한 사용자의 목록에는 개인 워크스페이스 하나가 관리자 역할로 보인다")
    void list_newUser_hasOnlyPersonalWorkspaceAsAdmin() throws Exception {
        String token = tokenOf(newUser());

        mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("내 워크스페이스"))
                .andExpect(jsonPath("$.data[0].personal").value(true))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].workspaceId").isNotEmpty())
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("워크스페이스를 만들면 만든 사람이 관리자이고, 목록에서 개인 워크스페이스 다음에 보인다")
    void create_creatorIsAdminAndListedAfterPersonal() throws Exception {
        String token = tokenOf(newUser());

        createWorkspace(token, "  우리 팀  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("우리 팀"))
                .andExpect(jsonPath("$.data.personal").value(false))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].personal").value(true))
                .andExpect(jsonPath("$.data[1].name").value("우리 팀"));
    }

    @Test
    @DisplayName("이름이 비었거나 50자를 넘으면 400이다")
    void create_invalidName_returns400() throws Exception {
        String token = tokenOf(newUser());

        createWorkspace(token, "   ").andExpect(status().isBadRequest());
        createWorkspace(token, "가".repeat(51)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("access token이 없으면 401이다")
    void list_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자는 개인 워크스페이스를 포함해 이름을 바꿀 수 있다")
    void rename_admin_succeedsIncludingPersonal() throws Exception {
        String token = tokenOf(newUser());
        String personalId = firstWorkspaceId(token);

        renameWorkspace(token, personalId, "개인 공간")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("개인 공간"))
                .andExpect(jsonPath("$.data.personal").value(true));
    }

    @Test
    @DisplayName("멤버는 이름을 바꿀 수 없고(403), 멤버가 아니면 워크스페이스가 없는 것처럼 404다")
    void rename_memberIs403AndOutsiderIs404() throws Exception {
        String adminToken = tokenOf(newUser());
        User member = newUser();
        String outsiderToken = tokenOf(newUser());
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        addMember(workspaceId, member);

        renameWorkspace(tokenOf(member), workspaceId, "바꿈")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_ACCESS_DENIED"));
        renameWorkspace(outsiderToken, workspaceId, "바꿈")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("개인 워크스페이스는 삭제할 수 없다")
    void delete_personal_returns409() throws Exception {
        String token = tokenOf(newUser());

        deleteWorkspace(token, firstWorkspaceId(token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("PERSONAL_WORKSPACE_UNDELETABLE"));
    }

    @Test
    @DisplayName("다른 멤버가 남아 있으면 유일한 관리자라도 삭제할 수 없다")
    void delete_withOtherMembers_returns409() throws Exception {
        String adminToken = tokenOf(newUser());
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        addMember(workspaceId, newUser());

        deleteWorkspace(adminToken, workspaceId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_HAS_MEMBERS"));
        assertThat(workspaceStatus(workspaceId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("삭제하면 워크스페이스가 목록에서 사라지고 소속 링크가 비활성화되며, 이후 요청은 404다")
    void delete_softDeletesWorkspaceAndDeactivatesLinks() throws Exception {
        User admin = newUser();
        String token = tokenOf(admin);
        String workspaceId = createdWorkspaceId(token, "지울 팀");
        String linkId = insertLink(workspaceId, admin);

        deleteWorkspace(token, workspaceId).andExpect(status().isOk());

        assertThat(workspaceStatus(workspaceId)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_visible FROM links WHERE link_id = ?", Boolean.class, linkId)).isFalse();
        mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.data.length()").value(1));
        renameWorkspace(token, workspaceId, "다시").andExpect(status().isNotFound());
    }

    private User newUser() {
        return userRegistrar.register(User.builder()
                .provider(SocialProvider.KAKAO)
                .socialId(SOCIAL_ID_PREFIX + UUID.randomUUID())
                .nickname("워크스페이스테스트")
                .email("ws@test.com")
                .build());
    }

    private String tokenOf(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getUserId());
    }

    private ResultActions createWorkspace(String token, String name) throws Exception {
        return mockMvc.perform(post("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(nameBody(name)));
    }

    private ResultActions renameWorkspace(String token, String workspaceId, String name) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(nameBody(name)));
    }

    private ResultActions deleteWorkspace(String token, String workspaceId) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/{workspaceId}", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private String createdWorkspaceId(String token, String name) throws Exception {
        String body = createWorkspace(token, name).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.workspaceId");
    }

    private String firstWorkspaceId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[0].workspaceId");
    }

    private void addMember(String workspaceId, User user) {
        jdbcTemplate.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role)
                SELECT id, ?, 'MEMBER' FROM workspaces WHERE workspace_id = ?
                """, user.getId(), workspaceId);
    }

    private String insertLink(String workspaceId, User creator) {
        String linkId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO links (link_id, user_id, workspace_id, slug, original_url, is_visible)
                SELECT ?, ?, id, ?, 'https://example.com', TRUE FROM workspaces WHERE workspace_id = ?
                """, linkId, creator.getId(), "ws" + UUID.randomUUID().toString().substring(0, 5), workspaceId);
        return linkId;
    }

    private String workspaceStatus(String workspaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM workspaces WHERE workspace_id = ?", String.class, workspaceId);
    }

    private static String nameBody(String name) {
        return """
                {"name":"%s"}
                """.formatted(name);
    }
}
