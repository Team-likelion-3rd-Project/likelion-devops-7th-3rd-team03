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
 * - 카카오 소셜 로그인 전용. 자체 회원가입(이메일/비밀번호)은 지원하지 않는다.
 * - 로그인 필수 정책: 익명 사용 없음
 * - 다른 서비스(link-service, stats-service)는 이 엔티티를 직접 참조하지 않고
 *   JWT 클레임(userId)만 신뢰하므로, User는 auth-service 내부에서만 사용된다.
 *
 * 식별자가 두 개인 이유:
 *   id      - 내부 조인용 auto increment. 외부에 노출하지 않는다
 *             (순번이 노출되면 가입자 수와 증가 속도가 추정된다).
 *   user_id - 외부 노출용 UUID. API 응답과 JWT 클레임에는 이 값을 쓴다.
 *             DB가 채워주지 않으므로 애플리케이션이 생성해야 한다(아래 builder에서 처리).
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_user_id", columnNames = "user_id"),
                @UniqueConstraint(name = "uk_users_kakao_id", columnNames = "kakao_id")
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

    /** 카카오 고유 회원번호. 로그인 시 이 값으로 기존 회원을 찾는다 */
    @Column(name = "kakao_id", nullable = false, updatable = false)
    private Long kakaoId;

    // 아래 3개는 카카오 "선택 동의항목"이라 사용자가 동의하지 않으면 NULL로 들어온다
    @Column(name = "nickname", length = 50)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "email", length = 100)
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
    private User(Long kakaoId, String nickname, String profileImageUrl, String email) {
        this.userId = UUID.randomUUID().toString();
        this.kakaoId = kakaoId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.email = email;
        this.status = UserStatus.ACTIVE;
    }

    /** 카카오 프로필은 언제든 바뀔 수 있으므로 로그인 때마다 최신값으로 갱신한다 */
    public void updateProfile(String nickname, String profileImageUrl, String email) {
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.email = email;
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
}
