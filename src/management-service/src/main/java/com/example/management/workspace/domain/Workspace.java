package com.example.management.workspace.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * workspaces 테이블 (V7__create_workspaces.sql 기준)
 *
 * 링크를 소유하는 팀. 가입하면 개인 워크스페이스가 하나 생기고(삭제·나가기 불가),
 * 사용자는 공유 워크스페이스를 만들거나 초대 코드로 합류한다.
 *
 * 식별자는 User·Link와 같은 방식이다:
 *   id           - 내부 조인용. 외부에 노출하지 않는다.
 *   workspace_id - 외부 노출용 UUID. API 경로 변수로 쓴다.
 *
 * 개인 워크스페이스는 personal_owner_id(UNIQUE)로 사용자당 1개가 DB에서 보장된다.
 * 삭제는 soft delete(status = DELETED)이고, 소속 링크의 비활성화는 서비스 레이어가 한다.
 */
@Entity
@Table(
        name = "workspaces",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_workspaces_workspace_id", columnNames = "workspace_id"),
                @UniqueConstraint(name = "uk_workspaces_personal_owner_id", columnNames = "personal_owner_id"),
                @UniqueConstraint(name = "uk_workspaces_invite_code", columnNames = "invite_code")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace {

    /** 개인 워크스페이스의 기본 이름 (V10에서 옮긴 데이터, 화면 시안과 같음) */
    public static final String PERSONAL_NAME = "내 워크스페이스";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 외부 노출용 식별자(UUID). NOT NULL이므로 반드시 애플리케이션이 채운다 */
    @Column(name = "workspace_id", length = 36, nullable = false, updatable = false)
    private String workspaceId;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "is_personal", nullable = false, updatable = false)
    private boolean isPersonal;

    /** 개인 워크스페이스의 주인 (users.id). 공유 워크스페이스는 null */
    @Column(name = "personal_owner_id", updatable = false)
    private Long personalOwnerId;

    /** 초대 코드. 처음 조회할 때 발급하고, 재발급하면 이전 코드는 무효 */
    @Column(name = "invite_code", length = 16)
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WorkspaceStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private Workspace(String name, boolean isPersonal, Long personalOwnerId) {
        this.workspaceId = UUID.randomUUID().toString();
        this.name = name;
        this.isPersonal = isPersonal;
        this.personalOwnerId = personalOwnerId;
        this.status = WorkspaceStatus.ACTIVE;
    }

    /** 가입할 때 만드는 개인 워크스페이스 */
    public static Workspace personal(Long ownerUserId) {
        return new Workspace(PERSONAL_NAME, true, ownerUserId);
    }

    /** 사용자가 만드는 공유 워크스페이스. 만든 사람을 관리자로 등록하는 것은 서비스 레이어가 한다 */
    public static Workspace shared(String name) {
        return new Workspace(name, false, null);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public void delete() {
        this.status = WorkspaceStatus.DELETED;
    }

    public boolean isActive() {
        return this.status == WorkspaceStatus.ACTIVE;
    }

    public enum WorkspaceStatus {
        ACTIVE, DELETED
    }
}
