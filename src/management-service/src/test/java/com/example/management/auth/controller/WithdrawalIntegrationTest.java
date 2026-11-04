package com.example.management.auth.controller;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.domain.User;
import com.example.management.auth.jwt.JwtTokenProvider;
import com.example.management.auth.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 탈퇴(#202)를 실제 MySQL·Redis와 Security 필터 체인까지 붙여 검증한다.
 * 탈퇴하면 social_id가 바뀌므로 테스트가 만든 회원은 내부 ID로 기억해 두고 지운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WithdrawalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRegistrar userRegistrar;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long userId : createdUserIds) {
            List<Long> workspaceIds = jdbcTemplate.queryForList("""
                    SELECT workspace_id FROM workspace_members WHERE user_id = ?
                    UNION SELECT id FROM workspaces WHERE personal_owner_id = ?
                    """, Long.class, userId, userId);
            for (Long workspaceId : workspaceIds) {
                jdbcTemplate.update("DELETE FROM links WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspace_members WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
            }
        }
        for (Long userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("탈퇴하면 개인 워크스페이스가 삭제되고 링크가 비활성화되며, 초대된 멤버도 접근할 수 없다")
    void personalWorkspace_deletedWithLinks() throws Exception {
        User owner = newUser("주인");
        User guest = newUser("손님");
        String personalId = firstWorkspaceId(tokenOf(owner));
        addMember(personalId, guest, "MEMBER", LocalDateTime.now());
        String linkId = createdLinkId(tokenOf(owner), personalId);

        withdraw(tokenOf(owner)).andExpect(status().isNoContent());

        assertThat(workspaceStatus(personalId)).isEqualTo("DELETED");
        assertThat(isVisible(linkId)).isFalse();
        mockMvc.perform(get("/api/workspaces/{id}/members", personalId).header(HttpHeaders.AUTHORIZATION, tokenOf(guest)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공유 워크스페이스의 유일한 관리자가 탈퇴하면 가장 먼저 합류한 멤버가 관리자가 되고, 링크는 남는다")
    void soleAdmin_succeededByOldestMember() throws Exception {
        User admin = newUser("관리자");
        User oldest = newUser("먼저 온 멤버");
        User newer = newUser("나중 멤버");
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        addMember(workspaceId, newer, "MEMBER", LocalDateTime.now().minusDays(1));
        addMember(workspaceId, oldest, "MEMBER", LocalDateTime.now().minusDays(2));
        String linkId = createdLinkId(tokenOf(admin), workspaceId);

        withdraw(tokenOf(admin)).andExpect(status().isNoContent());

        assertThat(workspaceStatus(workspaceId)).isEqualTo("ACTIVE");
        mockMvc.perform(get("/api/workspaces/{id}/members", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(newer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(oldest.getUserId()))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[1].userId").value(newer.getUserId()))
                .andExpect(jsonPath("$.data[1].role").value("MEMBER"));
        assertThat(isVisible(linkId)).isTrue();
        mockMvc.perform(get("/api/workspaces/{id}/links", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(newer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.links[0].linkId").value(linkId))
                .andExpect(jsonPath("$.data.links[0].createdBy").doesNotExist());
    }

    @Test
    @DisplayName("관리자가 여럿이면 탈퇴한 사람만 빠지고 다른 멤버의 역할은 그대로다")
    void oneOfManyAdmins_onlyLeaves() throws Exception {
        User admin = newUser("관리자1");
        User otherAdmin = newUser("관리자2");
        User member = newUser("멤버");
        String workspaceId = createdWorkspaceId(tokenOf(admin), "팀");
        addMember(workspaceId, member, "MEMBER", LocalDateTime.now().minusDays(2));
        addMember(workspaceId, otherAdmin, "ADMIN", LocalDateTime.now().minusDays(1));

        withdraw(tokenOf(admin)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/workspaces/{id}/members", workspaceId).header(HttpHeaders.AUTHORIZATION, tokenOf(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(otherAdmin.getUserId()))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[1].role").value("MEMBER"));
    }

    @Test
    @DisplayName("혼자 남은 공유 워크스페이스는 삭제되고 링크가 비활성화된다. 일반 멤버로 있던 워크스페이스에서는 빠지기만 한다")
    void soleMemberWorkspaceDeleted_memberWorkspaceJustLeft() throws Exception {
        User user = newUser("탈퇴자");
        User admin = newUser("다른 팀 관리자");
        String aloneId = createdWorkspaceId(tokenOf(user), "혼자 팀");
        String aloneLinkId = createdLinkId(tokenOf(user), aloneId);
        String otherId = createdWorkspaceId(tokenOf(admin), "다른 팀");
        addMember(otherId, user, "MEMBER", LocalDateTime.now());

        withdraw(tokenOf(user)).andExpect(status().isNoContent());

        assertThat(workspaceStatus(aloneId)).isEqualTo("DELETED");
        assertThat(isVisible(aloneLinkId)).isFalse();
        assertThat(workspaceStatus(otherId)).isEqualTo("ACTIVE");
        mockMvc.perform(get("/api/workspaces/{id}/members", otherId).header(HttpHeaders.AUTHORIZATION, tokenOf(admin)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(admin.getUserId()));
    }

    @Test
    @DisplayName("탈퇴하면 refresh token이 모두 지워지고, 남은 access token은 401이다")
    void tokensRevoked() throws Exception {
        User user = newUser("탈퇴자");
        String token = tokenOf(user);
        jdbcTemplate.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                user.getId(), "it-withdraw-" + UUID.randomUUID(), LocalDateTime.now().plusDays(7));

        withdraw(token).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Long.class, user.getId())).isZero();
        mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
        withdraw(token).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("탈퇴한 회원의 소셜 계정은 비워져, 같은 소셜 계정으로 새 회원·새 개인 워크스페이스를 만들 수 있다")
    void sameSocialAccount_canSignUpAgain() throws Exception {
        String socialId = "it-withdraw-" + UUID.randomUUID();
        User user = newUser(socialId, "탈퇴자");

        withdraw(tokenOf(user)).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT social_id FROM users WHERE id = ?", String.class, user.getId()))
                .isEqualTo(User.WITHDRAWN_SOCIAL_ID_PREFIX + user.getUserId());
        assertThat(userRepository.findByProviderAndSocialId(SocialProvider.KAKAO, socialId)).isEmpty();

        User again = newUser(socialId, "다시 가입");
        assertThat(again.getUserId()).isNotEqualTo(user.getUserId());
        mockMvc.perform(get("/api/workspaces").header(HttpHeaders.AUTHORIZATION, tokenOf(again)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].personal").value(true));
    }

    @Test
    @DisplayName("탈퇴하면 닉네임은 \"탈퇴한 사용자\", 이메일은 빈 문자열, 프로필 이미지는 null로 파기된다")
    void personalInfo_anonymized() throws Exception {
        User user = userRegistrar.register(User.builder()
                .provider(SocialProvider.KAKAO)
                .socialId("it-withdraw-" + UUID.randomUUID())
                .nickname("탈퇴자")
                .email("withdraw@test.com")
                .profileImageUrl("https://example.com/me.png")
                .build());
        createdUserIds.add(user.getId());

        withdraw(tokenOf(user)).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT nickname, email, profile_image_url FROM users WHERE id = ?", user.getId());
        assertThat(row.get("nickname")).isEqualTo(User.WITHDRAWN_NICKNAME);
        assertThat(row.get("email")).isEqualTo("");
        assertThat(row.get("profile_image_url")).isNull();
    }

    private User newUser(String nickname) {
        return newUser("it-withdraw-" + UUID.randomUUID(), nickname);
    }

    private User newUser(String socialId, String nickname) {
        User user = userRegistrar.register(User.builder()
                .provider(SocialProvider.KAKAO)
                .socialId(socialId)
                .nickname(nickname)
                .email("withdraw@test.com")
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private String tokenOf(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getUserId());
    }

    private ResultActions withdraw(String token) throws Exception {
        return mockMvc.perform(delete("/api/users/me").header(HttpHeaders.AUTHORIZATION, token));
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

    /** 승계 순서를 확실히 하려고 합류 시각을 직접 넣는다 */
    private void addMember(String workspaceId, User user, String role, LocalDateTime joinedAt) {
        jdbcTemplate.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role, joined_at)
                SELECT id, ?, ?, ? FROM workspaces WHERE workspace_id = ?
                """, user.getId(), role, joinedAt, workspaceId);
    }

    private String createdLinkId(String token, String workspaceId) throws Exception {
        String body = mockMvc.perform(post("/api/workspaces/{workspaceId}/links", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originalUrl":"https://example.com","title":"탈퇴 테스트","expiresAt":"%s"}
                                """.formatted(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.linkId");
    }

    private String workspaceStatus(String workspaceId) {
        return jdbcTemplate.queryForObject("SELECT status FROM workspaces WHERE workspace_id = ?", String.class, workspaceId);
    }

    private boolean isVisible(String linkId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_visible FROM links WHERE link_id = ?", Boolean.class, linkId));
    }
}
