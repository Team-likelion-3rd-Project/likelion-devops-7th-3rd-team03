-- =====================================================================
-- V8__create_workspace_members.sql
-- workspace_members 테이블 신규 추가 (#199)
--
-- 워크스페이스와 사용자의 소속 관계와 역할을 저장한다. 사용자는 여러 워크스페이스에 동시에 속할 수 있다.
-- 역할은 ADMIN(관리자)과 MEMBER(멤버) 두 가지이고, 초대 코드로 합류하면 MEMBER로 시작한다.
-- 워크스페이스마다 관리자는 최소 1명이어야 한다(앱이 검사).
-- joined_at은 회원 탈퇴로 유일한 관리자가 빠질 때 관리자를 넘겨받을 "가장 오래된 멤버"를 고르는 기준이다.
--
-- 실행 계정: shortlink_migrator
-- 선행: V7__create_workspaces.sql
-- =====================================================================

CREATE TABLE workspace_members (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '내부 식별자',
    workspace_id BIGINT       NOT NULL COMMENT '소속 워크스페이스 (workspaces.id)',
    user_id      BIGINT       NOT NULL COMMENT '멤버 (users.id)',
    role         VARCHAR(20)  NOT NULL COMMENT '역할 (ADMIN/MEMBER)',
    joined_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '합류 일시',
    CONSTRAINT uk_workspace_members_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT fk_workspace_members_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_workspace_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_workspace_members_role CHECK (role IN ('ADMIN', 'MEMBER')),
    -- 내 워크스페이스 목록 조회용 (UNIQUE의 앞 컬럼이 workspace_id라 user_id 단독 조회에는 못 쓴다)
    INDEX idx_workspace_members_user_id (user_id)
) ENGINE=InnoDB COMMENT '워크스페이스 멤버와 역할';
