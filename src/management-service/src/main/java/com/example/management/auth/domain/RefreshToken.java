package com.example.management.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * refresh_tokens 테이블 (V6__create_refresh_tokens.sql 기준)
 *
 * - JWT 리프레시 토큰을 저장한다. 원본 토큰 값이 아니라 해시(tokenHash)만 저장하므로
 *   DB가 유출되어도 토큰 자체를 복원할 수 없다.
 * - user_id는 users.id(내부 식별자)를 가리키지만, MSA 서비스 경계 원칙에 따라
 *   User와 JPA 연관관계를 맺지 않고 raw Long으로만 보관한다.
 */
@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash")
        },
        indexes = {
                @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 토큰 소유자 사용자 ID (users.id, 내부 FK). JPA 연관관계 없이 raw Long으로 보관 */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 리프레시 토큰 해시값. 원본 토큰은 저장하지 않는다 */
    @Column(name = "token_hash", length = 255, nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private RefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }
}
