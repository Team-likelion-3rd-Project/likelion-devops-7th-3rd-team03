package com.example.management.workspace.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * workspace_members 테이블 (V8__create_workspace_members.sql 기준)
 *
 * 워크스페이스와 사용자의 소속 관계와 역할. 사용자는 여러 워크스페이스에 동시에 속할 수 있다.
 * 워크스페이스마다 관리자가 최소 1명이어야 한다는 규칙은 서비스 레이어가 검사한다.
 *
 * Link의 userId처럼 연관관계 대신 내부 ID만 갖는다.
 */
@Entity
@Table(
        name = "workspace_members",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_workspace_members_workspace_user", columnNames = {"workspace_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_workspace_members_user_id", columnList = "user_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** workspaces.id */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /** users.id */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private WorkspaceRole role;

    /** 회원 탈퇴로 유일한 관리자가 빠질 때 관리자를 넘겨받을 가장 오래된 멤버를 고르는 기준 */
    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    private WorkspaceMember(Long workspaceId, Long userId, WorkspaceRole role) {
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.role = role;
    }

    /** 워크스페이스를 만든 사람(개인 워크스페이스의 주인 포함) */
    public static WorkspaceMember admin(Long workspaceId, Long userId) {
        return new WorkspaceMember(workspaceId, userId, WorkspaceRole.ADMIN);
    }

    /** 초대 코드로 합류한 사람은 멤버로 시작한다 */
    public static WorkspaceMember member(Long workspaceId, Long userId) {
        return new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER);
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public boolean isAdmin() {
        return this.role == WorkspaceRole.ADMIN;
    }
}
