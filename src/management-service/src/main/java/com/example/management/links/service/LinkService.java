package com.example.management.links.service;

import com.example.management.links.config.ShortUrlProperties;
import com.example.management.links.cache.RedirectCacheEntry;
import com.example.management.links.cache.RedirectCacheRefreshEvent;
import com.example.management.links.controller.dto.CreateLinkRequest;
import com.example.management.links.controller.dto.LinkListItemResponse;
import com.example.management.links.controller.dto.LinkListResponse;
import com.example.management.links.controller.dto.LinkResponse;
import com.example.management.links.controller.dto.LinkStatus;
import com.example.management.links.controller.dto.PaginationResponse;
import com.example.management.links.controller.dto.UpdateLinkRequest;
import com.example.management.links.controller.dto.UpdateLinkResponse;
import com.example.management.links.domain.Link;
import com.example.management.links.exception.InvalidExpirationException;
import com.example.management.links.exception.LinkLimitExceededException;
import com.example.management.links.exception.LinkNotFoundException;
import com.example.management.links.exception.NotLinkOwnerException;
import com.example.management.links.repository.LinkRepository;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.SecureRandom;

/** 링크 생성과 목록 조회 유스케이스. */
@Service
@RequiredArgsConstructor
public class LinkService {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SLUG_LENGTH = 7;
    private static final int MAX_SLUG_GENERATION_ATTEMPTS = 10;
    /** 운영 정책이 확정되기 전까지 서비스 상수로 관리한다. */
    private static final long MAX_VISIBLE_LINKS_PER_USER = 100;
    /** 운영 정책이 확정되기 전까지 서비스 상수로 관리한다. */
    private static final int MAX_EXPIRATION_YEARS = 3;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final LinkRepository linkRepository;
    private final ShortUrlProperties shortUrlProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public LinkResponse create(Long userId, CreateLinkRequest request) {
        // 1. 과거·최대 기간처럼 현재 시각에 의존하는 만료 정책은 Service에서 판단한다.
        validateExpirationPolicy(request.expiresAt());

        // 2. 삭제되지 않은 링크만 세어 사용자별 생성 제한을 적용한다.
        if (linkRepository.countByUserIdAndIsVisibleTrue(userId) >= MAX_VISIBLE_LINKS_PER_USER) {
            throw new LinkLimitExceededException();
        }

        // 3. 충돌하지 않는 slug를 가진 링크를 만든 뒤 저장한다.
        Link link = Link.builder()
                .userId(userId)
                .slug(generateAvailableSlug())
                .originalUrl(request.originalUrl())
                .title(request.title())
                .expiresAt(toUtcLocalDateTime(request.expiresAt()))
                .build();

        // 설정 오류로 저장만 되고 응답 생성이 실패하는 일을 막기 위해 영속화 전에 확인한다.
        String shortUrl = shortUrl(link.getSlug());
        Link savedLink = linkRepository.save(link);
        publishRedirectCacheRefresh(savedLink);
        return toResponse(savedLink, shortUrl);
    }

    @Transactional(readOnly = true)
    public LinkListResponse getList(Long userId, int page, int size) {
        // 1. 목록의 정렬 기준을 한 곳에서 고정한다. 같은 생성 시각은 내부 ID로 안정적으로 정렬한다.
        Pageable pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));

        // 2. Repository 쿼리 자체에 visible 조건을 포함해 soft-deleted 링크를 결과에서 제외한다.
        Page<Link> linkPage = linkRepository.findByUserIdAndIsVisibleTrue(userId, pageable);

        // 3. DB의 slug와 서버 설정의 base URL을 조합해 API 전용 목록 DTO로 변환한다.
        List<LinkListItemResponse> links = linkPage.getContent().stream()
                .map(this::toListItemResponse)
                .toList();

        return new LinkListResponse(links, new PaginationResponse(
                linkPage.getNumber(),
                linkPage.getSize(),
                linkPage.getTotalElements(),
                linkPage.getTotalPages()
        ));
    }

    @Transactional
    public UpdateLinkResponse update(Long userId, String linkId, UpdateLinkRequest request) {
        // 1. soft-deleted 링크를 제외하고 대상 링크를 찾는다.
        Link link = linkRepository.findByLinkIdAndIsVisibleTrue(linkId)
                .orElseThrow(LinkNotFoundException::new);

        // 2. 현재 사용자와 소유자가 다르면 수정 전 즉시 거절한다.
        if (!link.getUserId().equals(userId)) {
            throw new NotLinkOwnerException(linkId);
        }

        // 3. null 또는 미전달 필드는 수정하지 않는다.
        String originalUrl = request.originalUrl() != null ? request.originalUrl() : link.getOriginalUrl();
        String title = request.title() != null ? request.title() : link.getTitle();
        LocalDateTime expiresAt = link.getExpiresAt();
        if (request.expiresAt() != null) {
            validateExpirationPolicy(request.expiresAt());
            expiresAt = toUtcLocalDateTime(request.expiresAt());
        }

        // 4. Link.update는 slug와 linkId를 받지 않으므로 단축 URL 식별자는 유지된다.
        link.update(originalUrl, title, expiresAt);
        publishRedirectCacheRefresh(link);
        return toUpdateResponse(link);
    }

    @Transactional
    public void delete(Long userId, String linkId) {
        Link link = linkRepository.findByLinkIdAndIsVisibleTrue(linkId)
                .orElseThrow(LinkNotFoundException::new);

        if (!link.getUserId().equals(userId)) {
            throw new NotLinkOwnerException(linkId, "본인이 생성한 링크만 삭제할 수 있습니다.");
        }

        link.softDelete();
        // 키를 지우지 않고 isEnabled=false를 저장해 삭제된 인기 링크가 DB를 반복 조회하지 않게 한다.
        publishRedirectCacheRefresh(link);
    }

    private void validateExpirationPolicy(OffsetDateTime expiresAt) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusYears(MAX_EXPIRATION_YEARS))) {
            throw new InvalidExpirationException();
        }
    }

    private String generateAvailableSlug() {
        for (int attempt = 0; attempt < MAX_SLUG_GENERATION_ATTEMPTS; attempt++) {
            String slug = generateBase62Slug();
            if (!linkRepository.existsBySlug(slug)) {
                return slug;
            }
        }
        throw new IllegalStateException("단축 URL 생성에 실패했습니다. 다시 시도해주세요.");
    }

    private String generateBase62Slug() {
        StringBuilder slug = new StringBuilder(SLUG_LENGTH);
        for (int i = 0; i < SLUG_LENGTH; i++) {
            slug.append(BASE62.charAt(SECURE_RANDOM.nextInt(BASE62.length())));
        }
        return slug.toString();
    }

    private LinkResponse toResponse(Link link, String shortUrl) {
        return new LinkResponse(
                link.getLinkId(),
                link.getTitle(),
                shortUrl,
                link.getOriginalUrl(),
                toUtcOffsetDateTime(link.getCreatedAt()),
                toUtcOffsetDateTime(link.getExpiresAt()),
                link.isExpired(LocalDateTime.now(ZoneOffset.UTC)) ? LinkStatus.EXPIRED : LinkStatus.ACTIVE
        );
    }

    private LinkListItemResponse toListItemResponse(Link link) {
        return new LinkListItemResponse(
                link.getLinkId(),
                link.getTitle(),
                shortUrl(link.getSlug()),
                link.getOriginalUrl(),
                toUtcOffsetDateTime(link.getCreatedAt()),
                toUtcOffsetDateTime(link.getExpiresAt())
        );
    }

    private UpdateLinkResponse toUpdateResponse(Link link) {
        return new UpdateLinkResponse(
                link.getLinkId(),
                link.getTitle(),
                shortUrl(link.getSlug()),
                link.getOriginalUrl(),
                toUtcOffsetDateTime(link.getCreatedAt()),
                toUtcOffsetDateTime(link.getExpiresAt())
        );
    }

    private String shortUrl(String slug) {
        String baseUrl = shortUrlProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("SHORT_URL_BASE_URL 설정이 필요합니다.");
        }
        return baseUrl.replaceAll("/+$", "") + "/" + slug;
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime toUtcOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.UTC);
    }

    private void publishRedirectCacheRefresh(Link link) {
        // 이벤트에는 Entity가 아닌 현재 값의 스냅샷을 담아, 커밋 후에도 의도한 상태를 정확히 쓴다.
        eventPublisher.publishEvent(new RedirectCacheRefreshEvent(RedirectCacheEntry.from(link)));
    }
}
