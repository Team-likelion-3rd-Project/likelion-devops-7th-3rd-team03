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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멤버·초대 코드 API를 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * 사용자는 가입과 같은 경로(UserRegistrar)로 만들어 개인 워크스페이스가 함께 생기게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceMemberIntegrationTest {

    /** 테스트가 만든 회원만 지우기 위한 social_id 접두사 */
    private static final String SOCIAL_ID_PREFIX = "it-wsm-";

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
        List<Long> workspaceIds = jdbcTemplate.queryForList("""
                SELECT DISTINCT workspace_id FROM workspace_members WHERE user_id IN (%s)
                UNION SELECT id FROM workspaces WHERE personal_owner_id IN (%s)
                """.formatted(testUsers, testUsers), Long.class, SOCIAL_ID_PREFIX + "%", SOCIAL_ID_PREFIX + "%");
        for (Long workspaceId : workspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", workspaceId);
            jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE social_id LIKE ?", SOCIAL_ID_PREFIX + "%");
    }

    @Test
    @DisplayName("멤버 목록은 관리자가 먼저이고, 이메일과 본인 여부를 함께 준다. 멤버가 아니면 404다")
    void members_adminFirstWithEmailAndMe() throws Exception {
        User admin = newUser("관리자", "admin@test.com");
        User member = newUser("멤버", "member@test.com");
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        join(tokenOf(member), inviteCode(tokenOf(admin), workspaceId)).andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{id}/members", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(admin.getUserId()))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].email").value("admin@test.com"))
                .andExpect(jsonPath("$.data[0].me").value(false))
                .andExpect(jsonPath("$.data[1].nickname").value("멤버"))
                .andExpect(jsonPath("$.data[1].role").value("MEMBER"))
                .andExpect(jsonPath("$.data[1].me").value(true));

        mockMvc.perform(get("/api/workspaces/{id}/members", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(newUser())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("초대 코드는 처음 조회할 때 발급되고, 재발급하면 이전 코드로는 합류할 수 없다")
    void inviteCode_issuedOnceAndRegenerationInvalidatesOld() throws Exception {
        String adminToken = tokenOf(newUser());
        String workspaceId = createdWorkspaceId(adminToken, "팀");

        String first = inviteCode(adminToken, workspaceId);
        assertThat(first).matches("[A-HJ-NP-Z2-9]{12}");
        assertThat(inviteCode(adminToken, workspaceId)).isEqualTo(first);

        String body = mockMvc.perform(post("/api/workspaces/{id}/invite-code", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(body, "$.data.inviteCode");
        assertThat(second).isNotEqualTo(first);

        join(tokenOf(newUser()), first)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("INVALID_INVITE_CODE"));
        join(tokenOf(newUser()), "  " + second.toLowerCase() + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
    }

    @Test
    @DisplayName("관리자가 아니면 초대 코드를 볼 수 없다")
    void inviteCode_memberIs403() throws Exception {
        String adminToken = tokenOf(newUser());
        String memberToken = tokenOf(newUser());
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        join(memberToken, inviteCode(adminToken, workspaceId));

        mockMvc.perform(get("/api/workspaces/{id}/invite-code", workspaceId).header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 멤버면 409이고, 삭제된 워크스페이스의 코드로는 합류할 수 없다")
    void join_alreadyMemberAndDeletedWorkspace() throws Exception {
        String adminToken = tokenOf(newUser());
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        String code = inviteCode(adminToken, workspaceId);

        join(adminToken, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("ALREADY_MEMBER"));

        mockMvc.perform(delete("/api/workspaces/{id}", workspaceId).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        join(tokenOf(newUser()), code).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("마지막 관리자는 강등할 수 없고, 다른 관리자가 생기면 스스로 강등할 수 있다")
    void changeRole_lastAdminProtected() throws Exception {
        User admin = newUser();
        User member = newUser();
        String adminToken = tokenOf(admin);
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        join(tokenOf(member), inviteCode(adminToken, workspaceId));

        changeRole(adminToken, workspaceId, admin, "MEMBER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("LAST_ADMIN"));

        changeRole(adminToken, workspaceId, member, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        changeRole(adminToken, workspaceId, admin, "MEMBER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("MEMBER"));
    }

    @Test
    @DisplayName("멤버는 역할을 바꿀 수 없고, 멤버가 아닌 사용자를 대상으로 하면 404다")
    void changeRole_memberIs403AndUnknownTargetIs404() throws Exception {
        User admin = newUser();
        User member = newUser();
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        join(tokenOf(member), inviteCode(tokenOf(admin), workspaceId));

        changeRole(tokenOf(member), workspaceId, member, "ADMIN").andExpect(status().isForbidden());
        changeRole(tokenOf(admin), workspaceId, newUser(), "ADMIN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("WORKSPACE_MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("개인 워크스페이스의 주인은 다른 관리자가 강등하거나 내보낼 수 없다")
    void personalOwner_cannotBeDemotedOrRemoved() throws Exception {
        User owner = newUser();
        User guest = newUser();
        String ownerToken = tokenOf(owner);
        String personalId = firstWorkspaceId(ownerToken);
        join(tokenOf(guest), inviteCode(ownerToken, personalId)).andExpect(status().isOk());
        changeRole(ownerToken, personalId, guest, "ADMIN").andExpect(status().isOk());

        changeRole(tokenOf(guest), personalId, owner, "MEMBER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("PERSONAL_WORKSPACE_OWNER"));
        removeMember(tokenOf(guest), personalId, owner)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("PERSONAL_WORKSPACE_OWNER"));
    }

    @Test
    @DisplayName("관리자는 멤버를 내보낼 수 있고, 자기 자신은 내보낼 수 없다")
    void removeMember_adminRemovesOthersButNotSelf() throws Exception {
        User admin = newUser();
        User member = newUser();
        String adminToken = tokenOf(admin);
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        join(tokenOf(member), inviteCode(adminToken, workspaceId));

        removeMember(tokenOf(member), workspaceId, admin).andExpect(status().isForbidden());
        removeMember(adminToken, workspaceId, admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("CANNOT_REMOVE_SELF"));

        removeMember(adminToken, workspaceId, member).andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/{id}/members", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(member)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("멤버를 내보내면 초대 코드가 재발급되어, 내보낸 사람이 이전 코드로 다시 합류할 수 없다")
    void removeMember_regeneratesInviteCode() throws Exception {
        User admin = newUser();
        User member = newUser();
        String adminToken = tokenOf(admin);
        String workspaceId = createdWorkspaceId(adminToken, "팀");
        String oldCode = inviteCode(adminToken, workspaceId);
        join(tokenOf(member), oldCode).andExpect(status().isOk());

        removeMember(adminToken, workspaceId, member).andExpect(status().isOk());

        join(tokenOf(member), oldCode)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("INVALID_INVITE_CODE"));
        String newCode = inviteCode(adminToken, workspaceId);
        assertThat(newCode).isNotEqualTo(oldCode);
        join(tokenOf(newUser()), newCode).andExpect(status().isOk());
    }

    @Test
    @DisplayName("형식에 맞지 않는 초대 코드(한글 포함, 길이 다름)는 DB를 조회하지 않고 404다")
    void join_malformedCodeIs404() throws Exception {
        String token = tokenOf(newUser());

        for (String code : List.of("한글코드ABCDEFGH", "ABCDEFGHJK", "ABCDEFGHJKLM0")) {
            join(token, code)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.type").value("INVALID_INVITE_CODE"));
        }
    }

    @Test
    @DisplayName("개인 워크스페이스 주인·혼자 남은 멤버·마지막 관리자는 나갈 수 없고, 그 밖의 멤버는 나갈 수 있다")
    void leave_rules() throws Exception {
        User owner = newUser();
        User guest = newUser();
        String ownerToken = tokenOf(owner);
        String personalId = firstWorkspaceId(ownerToken);

        leave(ownerToken, personalId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("PERSONAL_WORKSPACE_LEAVE"));

        join(tokenOf(guest), inviteCode(ownerToken, personalId));
        leave(tokenOf(guest), personalId).andExpect(status().isOk());

        String sharedId = createdWorkspaceId(ownerToken, "팀");
        leave(ownerToken, sharedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("SOLE_MEMBER_CANNOT_LEAVE"));

        join(tokenOf(guest), inviteCode(ownerToken, sharedId));
        leave(ownerToken, sharedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("LAST_ADMIN"));
        leave(tokenOf(guest), sharedId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 두 명이 동시에 서로를 강등해도 관리자가 0명이 되지 않는다")
    void changeRole_concurrentMutualDemotion_keepsOneAdmin() throws Exception {
        for (int round = 0; round < 5; round++) {
            User first = newUser();
            User second = newUser();
            String workspaceId = createdWorkspaceId(tokenOf(first), "팀");
            join(tokenOf(second), inviteCode(tokenOf(first), workspaceId));
            changeRole(tokenOf(first), workspaceId, second, "ADMIN").andExpect(status().isOk());

            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> demoteAfter(start, first, workspaceId, second));
            CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> demoteAfter(start, second, workspaceId, first));
            start.countDown();

            // 먼저 잠근 요청이 상대를 강등하고, 기다리던 요청은 잠금을 얻은 뒤 자신이 더 이상 관리자가 아니라서 403이 된다
            assertThat(List.of(a.join(), b.join())).containsExactlyInAnyOrder(200, 403);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM workspace_members m JOIN workspaces w ON w.id = m.workspace_id
                     WHERE w.workspace_id = ? AND m.role = 'ADMIN'
                    """, Integer.class, workspaceId)).isEqualTo(1);
        }
    }

    private int demoteAfter(CountDownLatch start, User actor, String workspaceId, User target) {
        try {
            start.await();
            return changeRole(tokenOf(actor), workspaceId, target, "MEMBER").andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private User newUser() {
        return newUser("멤버테스트", "wsm@test.com");
    }

    private User newUser(String nickname, String email) {
        return userRegistrar.register(User.builder()
                .provider(SocialProvider.KAKAO)
                .socialId(SOCIAL_ID_PREFIX + UUID.randomUUID())
                .nickname(nickname)
                .email(email)
                .build());
    }

    private String tokenOf(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getUserId());
    }

    private String createdWorkspaceId(String token, String name) throws Exception {
        String body = mockMvc.perform(post("/api/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.workspaceId");
    }

    private String firstWorkspaceId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[0].workspaceId");
    }

    private String inviteCode(String adminToken, String workspaceId) throws Exception {
        String body = mockMvc.perform(get("/api/workspaces/{id}/invite-code", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.inviteCode");
    }

    private ResultActions join(String token, String inviteCode) throws Exception {
        return mockMvc.perform(post("/api/workspaces/join")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inviteCode\":\"%s\"}".formatted(inviteCode)));
    }

    private ResultActions changeRole(String token, String workspaceId, User target, String role) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/{id}/members/{userId}", workspaceId, target.getUserId())
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"%s\"}".formatted(role)));
    }

    private ResultActions removeMember(String token, String workspaceId, User target) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/{id}/members/{userId}", workspaceId, target.getUserId())
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions leave(String token, String workspaceId) throws Exception {
        return mockMvc.perform(post("/api/workspaces/{id}/leave", workspaceId)
                .header(HttpHeaders.AUTHORIZATION, token));
    }
}