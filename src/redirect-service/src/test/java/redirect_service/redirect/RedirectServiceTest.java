package redirect_service.redirect;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import redirect_service.exception.RedirectNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import redirect_service.clicklog.event.RedirectSucceededEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {

    @Mock
    private LinkRepository linkRepository;

    @Mock
    private RedisRedirectCache redirectCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private RedirectService redirectService;

    @BeforeEach
    void setUp() {
        redirectService = new RedirectService(linkRepository, redirectCache, eventPublisher, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("활성화되고 만료되지 않은 링크의 원본 URL을 반환한다")
    void returnsOriginalUrlForRedirectableLink() {
        Link link = link("valid", "https://example.com", true, null);
        when(redirectCache.get("valid")).thenReturn(Optional.empty());
        when(linkRepository.findBySlug("valid")).thenReturn(Optional.of(link));

        RedirectTarget redirectTarget = redirectService.findRedirectTarget("valid");

        assertThat(redirectTarget.linkId()).isEqualTo(1L);
        assertThat(redirectTarget.originalUrl()).isEqualTo("https://example.com");
        verify(linkRepository).findBySlug("valid");
        verify(redirectCache).put("valid", new RedirectCacheEntry(1L, "https://example.com", true, null));
    }

    @Test
    @DisplayName("성공한 리다이렉트의 visitorId와 요청 정보를 불변 이벤트로 발행한다")
    void publishesSnapshotAfterSuccessfulRedirect() {
        RedirectRequest request = new RedirectRequest(
                "valid", "visitor-id", LocalDateTime.parse("2026-08-12T15:00:00"), "203.0.113.10", null,
                "Mozilla/5.0", "https://google.com/search", null);
        when(redirectCache.get("valid")).thenReturn(Optional.of(
                new RedirectCacheEntry(1L, "https://example.com", true, null)));
        RedirectTarget result = redirectService.redirect(request);

        org.mockito.ArgumentCaptor<RedirectSucceededEvent> captor =
                org.mockito.ArgumentCaptor.forClass(RedirectSucceededEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(result.linkId()).isEqualTo(1L);
        assertThat(captor.getValue().linkId()).isEqualTo(1L);
        assertThat(captor.getValue().request())
                .extracting(event -> event.visitorId(), event -> event.remoteAddress(), event -> event.userAgent(),
                        event -> event.referrer())
                .containsExactly("visitor-id", "203.0.113.10", "Mozilla/5.0", "https://google.com/search");
        assertThat(captor.getValue().request().occurredAt()).isEqualTo(request.occurredAt());
    }

    @Test
    @DisplayName("캐시된 링크가 유효하면 DB를 조회하지 않는다")
    void returnsCachedRedirectTargetWithoutDatabaseLookup() {
        when(redirectCache.get("cached"))
                .thenReturn(Optional.of(new RedirectCacheEntry(1L, "https://example.com", true, null)));

        RedirectTarget redirectTarget = redirectService.findRedirectTarget("cached");

        assertThat(redirectTarget.linkId()).isEqualTo(1L);
        assertThat(redirectTarget.originalUrl()).isEqualTo("https://example.com");
        verifyNoInteractions(linkRepository);
    }

    @Test
    @DisplayName("비활성 캐시는 DB를 조회하지 않고 404를 반환한다")
    void throwsNotFoundForDisabledCachedLink() {
        when(redirectCache.get("disabled"))
                .thenReturn(Optional.of(new RedirectCacheEntry(1L, "https://example.com", false, null)));

        assertThatThrownBy(() -> redirectService.findRedirectTarget("disabled"))
                .isInstanceOf(RedirectNotFoundException.class);

        verifyNoInteractions(linkRepository);
    }

    @Test
    @DisplayName("존재하지 않는 링크는 404를 반환한다")
    void throwsNotFoundForMissingLink() {
        when(redirectCache.get("missing")).thenReturn(Optional.empty());
        when(linkRepository.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> redirectService.findRedirectTarget("missing"))
                .isInstanceOf(RedirectNotFoundException.class);
    }

    @Test
    @DisplayName("Soft 삭제된 링크는 404를 반환한다")
    void throwsNotFoundForInvisibleLink() {
        when(redirectCache.get("hidden")).thenReturn(Optional.empty());
        when(linkRepository.findBySlug("hidden"))
                .thenReturn(Optional.of(link("hidden", "https://example.com", false, null)));

        assertThatThrownBy(() -> redirectService.findRedirectTarget("hidden"))
                .isInstanceOf(RedirectNotFoundException.class);
        verify(redirectCache).put("hidden", new RedirectCacheEntry(1L, "https://example.com", false, null));
    }

    @Test
    @DisplayName("만료된 링크는 404를 반환한다")
    void throwsNotFoundForExpiredLink() {
        when(redirectCache.get("expired")).thenReturn(Optional.empty());
        when(linkRepository.findBySlug("expired"))
                .thenReturn(Optional.of(link("expired", "https://example.com", true,
                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))));

        assertThatThrownBy(() -> redirectService.findRedirectTarget("expired"))
                .isInstanceOf(RedirectNotFoundException.class);
    }

    @Test
    @DisplayName("만료된 캐시는 DB를 조회하지 않고 404를 반환한다")
    void throwsNotFoundForExpiredCachedLink() {
        when(redirectCache.get("expired-cache"))
                .thenReturn(Optional.of(new RedirectCacheEntry(1L, "https://example.com", true,
                        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))));

        assertThatThrownBy(() -> redirectService.findRedirectTarget("expired-cache"))
                .isInstanceOf(RedirectNotFoundException.class);

        verifyNoInteractions(linkRepository);
    }

    private Link link(String slug, String originalUrl, boolean isVisible, LocalDateTime expiresAt) {
        Link link = new Link();
        ReflectionTestUtils.setField(link, "id", 1L);
        ReflectionTestUtils.setField(link, "slug", slug);
        ReflectionTestUtils.setField(link, "originalUrl", originalUrl);
        ReflectionTestUtils.setField(link, "isVisible", isVisible);
        ReflectionTestUtils.setField(link, "expiresAt", expiresAt);
        return link;
    }
}
