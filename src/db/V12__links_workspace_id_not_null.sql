-- =====================================================================
-- V12__links_workspace_id_not_null.sql
-- links.workspace_id를 NOT NULL로 바꾼다 (#206)
--
-- 링크는 항상 워크스페이스 소유다. #204부터 앱은 모든 링크에 workspace_id를 채우고,
-- 그 전에 만들어진 링크는 V10·V11이 옮겼다. NULL이 남아 있으면 이 ALTER가 실패한다.
-- FK(fk_links_workspace)와 인덱스(idx_links_workspace_visible)는 그대로 둔다.
--
-- 이 파일이 적용된 뒤에는 앱 이미지를 #204(0aabceb) 이전으로 롤백하지 않는다.
-- 그 버전은 workspace_id 없이 링크를 만들어 INSERT가 실패한다.
--
-- 실행 계정: shortlink_migrator
-- 선행: V11__backfill_links_workspace.sql
-- =====================================================================

ALTER TABLE links
    MODIFY COLUMN workspace_id BIGINT NOT NULL COMMENT '소유 워크스페이스 (workspaces.id)';
