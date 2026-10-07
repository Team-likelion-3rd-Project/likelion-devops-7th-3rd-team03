package com.example.management.auth.domain;

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
 * users 테이블 (V1__init_schema.sql 기준)
 *
 * - 소셜 로그인 전용(카카오/구글/네이버). 자체 회원가입(이메일/비밀번호)은 지원하지 않는다.
 * - 로그인 필수 정책: 익명 사용 없음
 * - 다른 서비스(link-service, stats-service)는 이 엔티티를 직접 참조하지 않고
 *   JWT 클레임(userId)만 신뢰하므로, User는 auth-service 내부에서만 사용된다.
 *
 * 식별자가 두 개인 이유:
 *   id      - 내부 조인용 auto increment. 외부에 노출하지 않는다
 *             (순번이 노출되면 가입자 수와 증가 속도가 추정된다).
 *   user_id - 외부 노출용 UUID. API 응답과 JWT 클레임에는 이 값을 쓴다.
 *             DB가 채워주지 않으므로 애플리케이션이 생성해야 한다(아래 builder에서 처리).
 *
 * provider·socialId로 식별하는 이유:
 *   provider 안에서만 유일한 소셜 회원번호라, 다른 provider와 값이 우연히 같아도
 *   (provider, social_id) 복합 유니크로 충돌하지 않는다. 이메일로 계정을 병합하지 않는다
 *   (직접 입력한 이메일은 본인 확인이 안 된 값).
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_users_provider_social_id", columnNames = {"provider", "social_id"})
        },
        indexes = {
                @Index(name = "idx_users_created_at", columnList = "created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 외부 노출용 식별자(UUID). NOT NULL이므로 반드시 애플리케이션이 채운다 */
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private String userId;

    /** 소셜 로그인 제공자. 로그인 시 (provider, socialId)로 기존 회원을 찾는다 */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20, nullable = false, updatable = false)
    private SocialProvider provider;

    /** provider 내에서 유일한 소셜 회원 식별자. 숫자 ID도 문자열로 저장한다 */
    @Column(name = "social_id", length = 255, nullable = false, updatable = false)
    private String socialId;

    @Column(name = "nickname", length = 50, nullable = false)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "email", length = 100, nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private User(SocialProvider provider, String socialId, String nickname, String profileImageUrl, String email) {
        this.userId = UUID.randomUUID().toString();
        this.provider = provider;
        this.socialId = socialId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.email = email;
        this.status = UserStatus.ACTIVE;
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
    }

    public boolean isActive() {
        return this.status == UserStatus.ACTIVE;
    }

    public enum UserStatus {
        ACTIVE, WITHDRAWN
    }

    public enum SocialProvider {
        KAKAO, GOOGLE, NAVER
    }
}
