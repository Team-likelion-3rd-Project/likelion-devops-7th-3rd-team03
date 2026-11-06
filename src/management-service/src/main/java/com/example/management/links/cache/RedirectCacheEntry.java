package com.example.management.links.cache;

import com.example.management.links.domain.Link;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Redis Hash 계약. linkId는 links.id(PK)이며 redirect-service가 클릭 로그를 남길 때 사용한다.
 */
public record RedirectCacheEntry(
        Long linkId,
        String slug,
        String originalUrl,
        boolean isEnabled,
        LocalDateTime expiresAt
) {

    public static RedirectCacheEntry from(Link link) {
        // IDENTITY PK는 save 이후에만 확정된다. 로그에 필요한 PK 없이 캐시를 쓰지 않도록 막는다.
        return new RedirectCacheEntry(
                Objects.requireNonNull(link.getId(), "저장된 링크의 ID가 필요합니다."),
                link.getSlug(),
                link.getOriginalUrl(),
                link.isVisible(),
                link.getExpiresAt()
        );
    }
}
