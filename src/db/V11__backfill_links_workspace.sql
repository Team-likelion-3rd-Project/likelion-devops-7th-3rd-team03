-- =====================================================================
-- V11__backfill_links_workspace.sql
-- NOT NULL 전환 직전에 V10의 이전을 한 번 더 실행한다 (#206)
--
-- V10 이후 #204 배포 전까지(롤링 업데이트 중 포함) 이전 버전 앱이 만든 링크는 workspace_id가 NULL이다.
-- 링크만 옮기지 않고 V10의 세 단계를 모두 다시 실행한다. 그사이 개인 워크스페이스 없이 가입한 사용자가 있으면
-- 링크를 옮길 곳이 없어 V12가 실패하기 때문이다. dev에서 V10을 수동으로 다시 실행했는지와 관계없이 안전하다.
-- 모든 문장이 "아직 없는 것만" 처리하므로 이미 채워진 환경에서는 아무것도 바꾸지 않는다.
--
-- 규칙은 V10과 같다:
-- - 이름은 "내 워크스페이스", 탈퇴한 사용자(WITHDRAWN)의 개인 워크스페이스는 DELETED
-- - 초대 코드는 비워 둔다 (처음 조회할 때 앱이 발급)
-- - 링크를 옮길 때 updated_at을 그대로 둔다
--
-- 실행 계정: shortlink_migrator
-- 선행: V10__backfill_personal_workspaces.sql
-- =====================================================================

-- 1) 개인 워크스페이스 (아직 없는 사용자만)
INSERT INTO workspaces (workspace_id, name, is_personal, personal_owner_id, status, created_at)
SELECT UUID(),
       '내 워크스페이스',
       TRUE,
       u.id,
       CASE WHEN u.status = 'WITHDRAWN' THEN 'DELETED' ELSE 'ACTIVE' END,
       COALESCE(u.created_at, NOW())
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.personal_owner_id = u.id);

-- 2) 주인을 관리자로 등록 (아직 멤버가 아닌 경우만)
INSERT INTO workspace_members (workspace_id, user_id, role, joined_at)
SELECT w.id, w.personal_owner_id, 'ADMIN', COALESCE(w.created_at, NOW())
FROM workspaces w
WHERE w.is_personal = TRUE
  AND NOT EXISTS (
    SELECT 1 FROM workspace_members m
    WHERE m.workspace_id = w.id AND m.user_id = w.personal_owner_id
);

-- 3) 소유 워크스페이스가 없는 링크를 만든 사람의 개인 워크스페이스로
UPDATE links l
    JOIN workspaces w ON w.is_personal = TRUE AND w.personal_owner_id = l.user_id
    SET l.workspace_id = w.id,
        l.updated_at   = l.updated_at
WHERE l.workspace_id IS NULL;
