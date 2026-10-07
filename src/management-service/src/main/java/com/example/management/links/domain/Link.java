package com.example.management.links.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * links 테이블 (V1__init_schema.sql 기준)
 *
 * 식별자가 세 개인 이유:
 *   id      - 내부 조인용 auto increment. 외부에 노출하지 않는다.
 *   link_id - 외부 노출용 UUID. 링크 관리 API(수정/삭제/통계)의 경로 변수로 쓴다.
 *             DB가 채워주지 않으므로 애플리케이션이 생성한다(builder에서 처리).
 *   slug    - 리다이렉트 경로. 짧아야 하므로 UUID를 쓸 수 없다.
 *
 * slug 콜레이션:
 *   DB에서 CHARACTER SET ascii COLLATE ascii_bin 으로 선언되어 대소문자를 구분한다.
 *   테이블 기본 콜레이션(utf8mb4_0900_ai_ci)이었다면 Ab3f2Xz 와 ab3f2xz 가 같은 값으로
 *   취급되어 UNIQUE 충돌이 잦아지고 슬러그 공간이 62^7에서 36^7로 줄어든다.
 *   여기서는 columnDefinition을 쓰지 않는다 — 실제 DDL은 Flyway가 관리하고
 *   ddl-auto=validate 에서는 columnDefinition이 무시되므로 중복 선언이 혼란만 준다.
 *
 * userId를 @ManyToOne으로 걸지 않은 이유:
 *   auth-service와 link-service가 분리되어 있고 소유권 검증은 JWT 클레임으로 하기 때문.
 *   DB 레벨 FK는 V1에 존재하지만 JPA 연관관계로는 매핑하지 않는다.
 */
@Entity
@Table(
        name = "links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_links_link_id", columnNames = "link_id"),
                @UniqueConstraint(name = "uk_links_slug", columnNames = "slug")
        },
        indexes = {
                // 목록 조회는 항상 is_visible = true 필터가 붙는다
                @Index(name = "idx_links_user_visible", columnList = "user_id, is_visible"),
                @Index(name = "idx_links_slug_visible", columnList = "slug, is_visible"),
                @Index(name = "idx_links_expires_at", columnList = "expires_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link {

    /** DB의 chk_slug_length CHECK 제약과 같은 값. 서비스 레이어 검증에도 이 상수를 쓸 것 */
    public static final int SLUG_MIN_LENGTH = 4;
    public static final int SLUG_MAX_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 외부 노출용 식별자(UUID). NOT NULL이므로 반드시 애플리케이션이 채운다 */
    @Column(name = "link_id", length = 36, nullable = false, updatable = false)
    private String linkId;

    /** 소유자 (users.id). DB에 FK가 있으나 JPA 연관관계로는 매핑하지 않는다 */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 리다이렉트 경로. 대소문자를 구분한다 */
    @Column(name = "slug", length = SLUG_MAX_LENGTH, nullable = false, updatable = false)
    private String slug;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "original_url", length = 2048, nullable = false)
    private String originalUrl;

    /** soft-delete 플래그. false여도 slug는 영구 점유되어 재사용할 수 없다 */
    @Column(name = "is_visible", nullable = false)
    private boolean isVisible;

    /** 만료일시. NULL이면 무기한 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Link(Long userId, String slug, String originalUrl, String title, LocalDateTime expiresAt) {
        this.linkId = UUID.randomUUID().toString();
        this.userId = userId;
        this.slug = slug;
        this.originalUrl = originalUrl;
        this.title = title;
        this.expiresAt = expiresAt;
        this.isVisible = true;
    }

    /** 원본 URL / 제목 / 만료일 수정. slug는 바꾸지 않는다(기존에 공유된 단축 링크가 깨진다) */
    public void update(String originalUrl, String title, LocalDateTime expiresAt) {
        this.originalUrl = originalUrl;
        this.title = title;
        this.expiresAt = expiresAt;
    }

    public void softDelete() {
        this.isVisible = false;
    }

    /**
     * 만료 여부. 리다이렉트 전에 반드시 검사할 것.
     * Redis 캐시에서 꺼낸 경우에도 검사해야 한다 — 캐시에는 만료된 링크가 남아 있을 수 있다.
     */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** 리다이렉트 가능 여부 (노출 상태 + 만료 여부를 한 번에 판단) */
    public boolean isRedirectable(LocalDateTime now) {
        return isVisible && !isExpired(now);
    }
}
