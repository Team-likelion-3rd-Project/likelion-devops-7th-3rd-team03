-- =====================================================================
-- V9__add_links_workspace_id.sql
-- links.workspace_id 추가, links.user_id를 "만든 사람"으로 (#199)
--
-- 링크의 소유자가 사용자에서 워크스페이스로 바뀐다.
-- 마이그레이션 작성 규칙(README "마이그레이션 작성 규칙")에 따라 이번에는 추가만 한다:
--   - workspace_id는 NULL 허용으로 추가한다. 이전 버전 앱은 이 컬럼을 모른 채 링크를 만든다.
--     NOT NULL 전환은 새 앱이 운영에 배포된 다음 릴리스에서 한다(#206).
--   - user_id는 지우거나 이름을 바꾸지 않고, 의미만 "링크를 만든 사람"으로 바꾼다(주석만 변경).
-- 기존 링크의 workspace_id는 V10이 채운다.
--
-- 실행 계정: shortlink_migrator
-- 선행: V7__create_workspaces.sql
-- =====================================================================

ALTER TABLE links
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '링크를 만든 사람 (users.id). 소유는 workspace_id',
    ADD COLUMN workspace_id BIGINT NULL COMMENT '소유 워크스페이스 (workspaces.id). 이후 NOT NULL로 전환 예정' AFTER user_id,
    ADD CONSTRAINT fk_links_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    -- 워크스페이스 링크 목록 조회는 항상 is_visible = true 필터가 붙는다 (기존 idx_links_user_visible과 같은 이유)
    ADD INDEX idx_links_workspace_visible (workspace_id, is_visible);
