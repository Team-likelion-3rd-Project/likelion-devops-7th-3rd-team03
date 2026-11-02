-- =====================================================================
-- V7__create_workspaces.sql
-- workspaces 테이블 신규 추가 (#199)
--
-- 링크를 개인이 아닌 팀(워크스페이스)이 소유하도록 바꾸는 첫 단계다.
-- 가입하면 개인 워크스페이스가 하나 생기고, 사용자는 공유 워크스페이스를 만들거나 초대 코드로 합류한다.
-- 삭제는 soft delete(status = DELETED)이고, 그 워크스페이스의 링크도 비활성화된다(앱이 처리).
--
-- 실행 계정: shortlink_migrator
-- 선행: V1__init_schema.sql (users 테이블)
-- =====================================================================

CREATE TABLE workspaces (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '내부 식별자 (조인용)',
    workspace_id      VARCHAR(36)  NOT NULL COMMENT '외부 노출용 워크스페이스 식별자 (UUID)',
    name              VARCHAR(50)  NOT NULL COMMENT '워크스페이스 이름',
    is_personal       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '개인 워크스페이스 여부 (가입 시 자동 생성, 삭제·나가기 불가)',
    personal_owner_id BIGINT       NULL COMMENT '개인 워크스페이스의 주인 (users.id). 공유 워크스페이스는 NULL',
    -- slug와 같은 이유로 대소문자를 구분한다 (V1의 links.slug 참고)
    invite_code       VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '초대 코드. 처음 조회할 때 앱이 발급하고, 재발급하면 이전 코드는 무효',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '상태 (ACTIVE/DELETED)',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',
    CONSTRAINT uk_workspaces_workspace_id UNIQUE (workspace_id),
    -- 사용자당 개인 워크스페이스는 1개. UNIQUE는 NULL을 중복으로 보지 않으므로 공유 워크스페이스는 영향이 없다
    CONSTRAINT uk_workspaces_personal_owner_id UNIQUE (personal_owner_id),
    CONSTRAINT uk_workspaces_invite_code UNIQUE (invite_code),
    CONSTRAINT fk_workspaces_personal_owner FOREIGN KEY (personal_owner_id) REFERENCES users(id),
    -- 개인 워크스페이스는 주인이 있고, 공유 워크스페이스는 주인이 없다
    CONSTRAINT chk_workspaces_personal_owner CHECK (
        (is_personal = TRUE AND personal_owner_id IS NOT NULL) OR (is_personal = FALSE AND personal_owner_id IS NULL)
    ),
    CONSTRAINT chk_workspaces_status CHECK (status IN ('ACTIVE', 'DELETED'))
) ENGINE=InnoDB COMMENT '워크스페이스(팀). 링크를 소유한다';
