package com.example.management.links.service;

import com.example.management.auth.domain.SocialProvider;
import com.example.management.auth.domain.User;
import com.example.management.auth.repository.UserRepository;
import com.example.management.links.config.ShortUrlProperties;
import com.example.management.links.controller.dto.CreateLinkRequest;
import com.example.management.links.controller.dto.LinkCreatorResponse;
import com.example.management.links.controller.dto.LinkListResponse;
import com.example.management.links.controller.dto.UpdateLinkRequest;
import com.example.management.links.controller.dto.UpdateLinkResponse;
import com.example.management.links.controller.dto.LinkResponse;
import com.example.management.links.controller.dto.LinkStatus;
import com.example.management.links.domain.Link;
import com.example.management.links.exception.InvalidExpirationException;
import com.example.management.links.exception.LinkLimitExceededException;
import com.example.management.links.exception.LinkNotFoundException;
import com.example.management.links.repository.LinkRepository;
import com.example.management.links.cache.RedirectCacheEntry;
import com.example.management.links.cache.RedirectCacheRefreshEvent;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.exception.WorkspaceNotFoundException;
import com.example.management.workspace.service.WorkspaceAccess;
import com.example.management.workspace.service.WorkspaceAccessChecker;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    private static final String WORKSPACE_UUID = "9b2f1c3a-0d4e-4f5a-8b6c-7d8e9f0a1b2c";
    private static final Long WORKSPACE_ID = 7L;

    @Mock
    private LinkRepository linkRepository;

    @Mock
    private WorkspaceAccessChecker workspaceAccessChecker;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private LinkService linkService;

    @BeforeEach
    void setUp() {
        linkService = new LinkService(linkRepository, workspaceAccessChecker, userRepository,
                new ShortUrlProperties("https://s.short.ly"), eventPublisher);
    }

    @Test
    @DisplayName("create: 유효한 요청이면 워크스페이스 소유, 현재 사용자가 만든 랜덤 Base62 링크를 저장하고 반환한다")
    void create_validRequest_savesLinkAndReturnsResponse() {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        CreateLinkRequest request = new CreateLinkRequest("https://example.com/very/long/path?query=1", expiresAt, "my-blog");

        givenMember();
        when(linkRepository.existsBySlug(anyString())).thenReturn(false);
        saveWithCreatedAt();

        LinkResponse response = linkService.create(10L, WORKSPACE_UUID, request);

        ArgumentCaptor<Link> linkCaptor = ArgumentCaptor.forClass(Link.class);
        verify(linkRepository).save(linkCaptor.capture());
        Link saved = linkCaptor.getValue();
        assertThat(saved.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(saved.getUserId()).isEqualTo(10L);
        assertThat(saved.getTitle()).isEqualTo("my-blog");
        assertThat(saved.getOriginalUrl()).isEqualTo(request.originalUrl());
        assertThat(saved.getSlug()).matches("[0-9A-Za-z]{7}");
        assertThat(response.linkId()).isEqualTo(saved.getLinkId());
        assertThat(response.shortUrl()).isEqualTo("https://s.short.ly/" + saved.getSlug());
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        assertThat(response.status()).isEqualTo(LinkStatus.ACTIVE);
        verifyRedirectCacheRefresh(saved, true);
    }

    @Test
    @DisplayName("create: 워크스페이스 멤버가 아니면 저장하지 않고 워크스페이스 없음 오류를 던진다")
    void create_nonMember_throwsWorkspaceNotFoundAndDoesNotSave() {
        givenNonMember();

        assertThrows(WorkspaceNotFoundException.class, () -> linkService.create(10L, WORKSPACE_UUID,
                new CreateLinkRequest("https://example.com", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), "title")));

        verifyNoInteractions(linkRepository);
    }

    @Test
    @DisplayName("create: 이미 만료된 expiresAt이면 저장하지 않고 요청 오류를 던진다")
    void create_expiredAt_throwsAndDoesNotSave() {
        CreateLinkRequest request = new CreateLinkRequest(
                "https://example.com", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), "expired");
        givenMember();

        assertThrows(InvalidExpirationException.class, () -> linkService.create(10L, WORKSPACE_UUID, request));

        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: expiresAt은 현재로부터 3년을 초과할 수 없다")
    void create_expiresAtMoreThanThreeYearsAway_throwsAndDoesNotAccessRepositories() {
        CreateLinkRequest request = new CreateLinkRequest(
                "https://example.com", OffsetDateTime.now(ZoneOffset.UTC).plusYears(3).plusSeconds(1), "too-far");
        givenMember();

        assertThrows(InvalidExpirationException.class, () -> linkService.create(10L, WORKSPACE_UUID, request));

        verifyNoInteractions(linkRepository);
    }

    @Test
    @DisplayName("create: 워크스페이스의 visible 링크가 100개면 저장하지 않고 링크 제한 오류를 던진다")
    void create_linkLimitExceeded_throwsAndDoesNotSave() {
        givenMember();
        when(linkRepository.countByWorkspaceIdAndIsVisibleTrue(WORKSPACE_ID)).thenReturn(100L);

        assertThrows(LinkLimitExceededException.class, () -> linkService.create(
                10L, WORKSPACE_UUID, new CreateLinkRequest("https://example.com", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), "title")));

        verify(linkRepository, never()).save(any());
        verify(linkRepository, never()).existsBySlug(anyString());
    }

    @Test
    @DisplayName("create: 이미 사용 중인 slug가 나오면 새 slug를 생성해 저장한다")
    void create_slugCollision_generatesAnotherSlug() {
        givenMember();
        when(linkRepository.existsBySlug(anyString())).thenReturn(true, false);
        saveWithCreatedAt();

        linkService.create(10L, WORKSPACE_UUID, new CreateLinkRequest(
                "https://example.com", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), "title"));

        verify(linkRepository, org.mockito.Mockito.times(2)).existsBySlug(anyString());
        verify(linkRepository).save(any(Link.class));
    }

    @Test
    @DisplayName("create: short URL base 설정이 없으면 저장 전에 실패한다")
    void create_missingShortUrlBase_throwsBeforeSave() {
        LinkService serviceWithoutBaseUrl = new LinkService(linkRepository, workspaceAccessChecker, userRepository,
                new ShortUrlProperties(" "), eventPublisher);
        givenMember();
        when(linkRepository.existsBySlug(anyString())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> serviceWithoutBaseUrl.create(
                10L, WORKSPACE_UUID, new CreateLinkRequest("https://example.com", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), "title")));

        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("getList: 워크스페이스의 visible 링크만 최신 생성 순으로 조회해 만든 사람과 페이지 정보를 함께 반환한다")
    void getList_returnsLinksAndPagination() {
        Link newest = link("newest", "new-title", "Ab3dE9f",
                LocalDateTime.of(2026, 8, 10, 12, 0), LocalDateTime.of(2027, 8, 10, 0, 0));
        Link older = link("older", null, "Xy9kLm2",
                LocalDateTime.of(2026, 8, 9, 12, 0), LocalDateTime.of(2027, 8, 9, 0, 0));
        Page<Link> resultPage = mock();
        when(resultPage.getContent()).thenReturn(List.of(newest, older));
        when(resultPage.getNumber()).thenReturn(0);
        when(resultPage.getSize()).thenReturn(20);
        when(resultPage.getTotalElements()).thenReturn(12L);
        when(resultPage.getTotalPages()).thenReturn(1);
        givenMember();
        when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(eq(WORKSPACE_ID), any(Pageable.class))).thenReturn(resultPage);
        User creator = User.builder()
                .provider(SocialProvider.KAKAO).socialId("1").nickname("홍길동")
                .profileImageUrl("https://img.example.com/a.png").email("a@example.com")
                .build();
        ReflectionTestUtils.setField(creator, "id", 10L);
        when(userRepository.findAllById(List.of(10L))).thenReturn(List.of(creator));

        LinkListResponse response = linkService.getList(10L, WORKSPACE_UUID, 0, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(linkRepository).findByWorkspaceIdAndIsVisibleTrue(eq(WORKSPACE_ID), pageableCaptor.capture());
        Pageable requestedPage = pageableCaptor.getValue();
        assertThat(requestedPage.getPageNumber()).isZero();
        assertThat(requestedPage.getPageSize()).isEqualTo(20);
        assertThat(requestedPage.getSort()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

        assertThat(response.links()).hasSize(2);
        assertThat(response.links().get(0).linkId()).isEqualTo(newest.getLinkId());
        assertThat(response.links().get(0).shortUrl()).isEqualTo("https://s.short.ly/Ab3dE9f");
        assertThat(response.links().get(0).createdBy()).isEqualTo(new LinkCreatorResponse(
                creator.getUserId(), "홍길동", "https://img.example.com/a.png"));
        assertThat(response.links().get(0).createdAt())
                .isEqualTo(OffsetDateTime.of(2026, 8, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        assertThat(response.links().get(0).expiresAt())
                .isEqualTo(OffsetDateTime.of(2027, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(response.pagination().page()).isZero();
        assertThat(response.pagination().size()).isEqualTo(20);
        assertThat(response.pagination().totalElements()).isEqualTo(12);
        assertThat(response.pagination().totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("getList: 조회 결과가 없으면 빈 목록과 totalPages 0을 반환한다")
    void getList_returnsEmptyListWhenNoVisibleLinksExist() {
        Page<Link> emptyPage = Page.empty(PageRequest.of(0, 20));
        givenMember();
        when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(eq(WORKSPACE_ID), any(Pageable.class))).thenReturn(emptyPage);

        LinkListResponse response = linkService.getList(10L, WORKSPACE_UUID, 0, 20);

        assertThat(response.links()).isEmpty();
        assertThat(response.pagination().page()).isZero();
        assertThat(response.pagination().size()).isEqualTo(20);
        assertThat(response.pagination().totalElements()).isZero();
        assertThat(response.pagination().totalPages()).isZero();
    }

    @Test
    @DisplayName("update: 워크스페이스 멤버가 전달한 원본 URL·제목·만료일만 수정하고 slug와 생성 시각은 유지한다")
    void update_ownerChangesProvidedFields_keepsSlugAndCreatedAt() {
        Link link = link("before", "before-title", "Ab3dE9f",
                LocalDateTime.of(2026, 8, 10, 12, 0), LocalDateTime.of(2027, 8, 10, 0, 0));
        UpdateLinkRequest request = new UpdateLinkRequest(
                "https://example.com/after",
                "after-title",
                OffsetDateTime.of(2027, 8, 11, 0, 0, 0, 0, ZoneOffset.UTC)
        );
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue(link.getLinkId(), WORKSPACE_ID))
                .thenReturn(Optional.of(link));

        UpdateLinkResponse response = linkService.update(10L, WORKSPACE_UUID, link.getLinkId(), request);

        assertThat(link.getOriginalUrl()).isEqualTo("https://example.com/after");
        assertThat(link.getTitle()).isEqualTo("after-title");
        assertThat(link.getExpiresAt()).isEqualTo(LocalDateTime.of(2027, 8, 11, 0, 0));
        assertThat(link.getSlug()).isEqualTo("Ab3dE9f");
        assertThat(link.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 10, 12, 0));
        assertThat(response.shortUrl()).isEqualTo("https://s.short.ly/Ab3dE9f");
        assertThat(response.createdAt()).isEqualTo(OffsetDateTime.of(2026, 8, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        verifyRedirectCacheRefresh(link, true);
    }

    @Test
    @DisplayName("update: 만료된 링크도 미래 만료일로 수정할 수 있다")
    void update_expiredLinkWithFutureExpiration_succeeds() {
        Link link = link("before", "title", "Ab3dE9f",
                LocalDateTime.of(2026, 8, 10, 12, 0), LocalDateTime.of(2026, 8, 11, 0, 0));
        OffsetDateTime futureExpiration = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
        UpdateLinkRequest request = new UpdateLinkRequest(null, null, futureExpiration);
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue(link.getLinkId(), WORKSPACE_ID))
                .thenReturn(Optional.of(link));

        linkService.update(10L, WORKSPACE_UUID, link.getLinkId(), request);

        assertThat(link.getExpiresAt()).isEqualTo(futureExpiration.toLocalDateTime());
    }

    @Test
    @DisplayName("update: 링크가 없거나 soft-deleted 상태거나 다른 워크스페이스의 링크면 링크 없음 오류를 던진다")
    void update_linkNotFound_throwsNotFoundException() {
        UpdateLinkRequest request = new UpdateLinkRequest(null, "title", null);
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue("missing-link-id", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThrows(LinkNotFoundException.class, () -> linkService.update(10L, WORKSPACE_UUID, "missing-link-id", request));
    }

    @Test
    @DisplayName("update: 같은 워크스페이스 멤버면 다른 사람이 만든 링크도 수정한다")
    void update_teammateChangesOthersLink() {
        Link link = Link.builder()
                .userId(20L)
                .workspaceId(WORKSPACE_ID)
                .slug("Ab3dE9f")
                .originalUrl("https://example.com/before")
                .title("title")
                .expiresAt(LocalDateTime.of(2027, 8, 10, 0, 0))
                .build();
        ReflectionTestUtils.setField(link, "id", 1L);
        UpdateLinkRequest request = new UpdateLinkRequest(null, "after-title", null);
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue(link.getLinkId(), WORKSPACE_ID))
                .thenReturn(Optional.of(link));

        linkService.update(10L, WORKSPACE_UUID, link.getLinkId(), request);

        assertThat(link.getTitle()).isEqualTo("after-title");
    }

    @Test
    @DisplayName("delete: 링크가 없거나 soft-deleted 상태거나 다른 워크스페이스의 링크면 LinkNotFoundException을 던진다")
    void delete_linkNotFound_throwsLinkNotFoundException() {
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue("missing-link-id", WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThrows(LinkNotFoundException.class, () -> linkService.delete(10L, WORKSPACE_UUID, "missing-link-id"));
    }

    @Test
    @DisplayName("delete: 워크스페이스 멤버가 아니면 링크를 조회하지 않고 워크스페이스 없음 오류를 던진다")
    void delete_nonMember_throwsWorkspaceNotFound() {
        givenNonMember();

        assertThrows(WorkspaceNotFoundException.class, () -> linkService.delete(10L, WORKSPACE_UUID, "link-id"));

        verifyNoInteractions(linkRepository);
    }

    @Test
    @DisplayName("delete: 키를 제거하지 않고 비활성 캐시 갱신 이벤트를 발행한다")
    void delete_ownerMarksLinkInvisibleAndPublishesInactiveCacheRefresh() {
        Link link = link("before", "title", "Ab3dE9f",
                LocalDateTime.of(2026, 8, 10, 12, 0), LocalDateTime.of(2027, 8, 10, 0, 0));
        givenMember();
        when(linkRepository.findByLinkIdAndWorkspaceIdAndIsVisibleTrue(link.getLinkId(), WORKSPACE_ID))
                .thenReturn(Optional.of(link));

        linkService.delete(10L, WORKSPACE_UUID, link.getLinkId());

        assertThat(link.isVisible()).isFalse();
        verifyRedirectCacheRefresh(link, false);
    }

    @Test
    @DisplayName("deactivateAllInWorkspace: 워크스페이스의 링크를 모두 비활성화하고 링크마다 비활성 캐시 갱신 이벤트를 발행한다")
    void deactivateAllInWorkspace_softDeletesEachLinkAndPublishesInactiveCacheRefresh() {
        Link first = link("first", "첫째", "AbC1234", LocalDateTime.of(2026, 8, 10, 12, 0), null);
        Link second = link("second", "둘째", "XyZ9876", LocalDateTime.of(2026, 8, 11, 12, 0), null);
        ReflectionTestUtils.setField(second, "id", 2L);
        when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(7L)).thenReturn(List.of(first, second));

        linkService.deactivateAllInWorkspace(7L);

        assertThat(first.isVisible()).isFalse();
        assertThat(second.isVisible()).isFalse();
        ArgumentCaptor<RedirectCacheRefreshEvent> eventCaptor =
                ArgumentCaptor.forClass(RedirectCacheRefreshEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(event -> event.entry().linkId(), event -> event.entry().isEnabled())
                .containsExactly(
                        tuple(1L, false),
                        tuple(2L, false));
    }

    private void givenMember() {
        Workspace workspace = Workspace.shared("팀");
        ReflectionTestUtils.setField(workspace, "id", WORKSPACE_ID);
        when(workspaceAccessChecker.requireMember(WORKSPACE_UUID, 10L))
                .thenReturn(new WorkspaceAccess(workspace, WorkspaceMember.member(WORKSPACE_ID, 10L)));
    }

    private void givenNonMember() {
        when(workspaceAccessChecker.requireMember(WORKSPACE_UUID, 10L)).thenThrow(new WorkspaceNotFoundException());
    }

    private Link link(String originalUrl, String title, String slug,
                      LocalDateTime createdAt, LocalDateTime expiresAt) {
        Link link = Link.builder()
                .userId(10L)
                .slug(slug)
                .originalUrl("https://example.com/" + originalUrl)
                .title(title)
                .expiresAt(expiresAt)
                .build();
        ReflectionTestUtils.setField(link, "createdAt", createdAt);
        ReflectionTestUtils.setField(link, "id", 1L);
        return link;
    }

    private void saveWithCreatedAt() {
        when(linkRepository.save(any(Link.class))).thenAnswer(invocation -> {
            Link saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 1L);
            ReflectionTestUtils.setField(saved, "createdAt", LocalDateTime.of(2026, 8, 10, 12, 0));
            return saved;
        });
    }

    private void verifyRedirectCacheRefresh(Link link, boolean isEnabled) {
        ArgumentCaptor<RedirectCacheRefreshEvent> eventCaptor =
                ArgumentCaptor.forClass(RedirectCacheRefreshEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().entry()).isEqualTo(new RedirectCacheEntry(
                link.getId(), link.getSlug(), link.getOriginalUrl(), isEnabled, link.getExpiresAt()
        ));
    }
}
