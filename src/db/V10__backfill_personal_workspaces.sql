-- =====================================================================
-- V10__backfill_personal_workspaces.sql
-- 기존 사용자마다 개인 워크스페이스를 만들고 기존 링크를 옮긴다 (#199)
--
-- 앞으로는 가입할 때 앱이 개인 워크스페이스를 만든다. 이 파일은 그 전에 가입한 사용자를 채운다.
-- DDL이 없고, 모든 문장이 "아직 없는 것만" 처리하므로 중간에 실패해도 다시 실행하면 이어서 채운다.
-- 이후 NOT NULL 전환 때도 같은 이전을 한 번 더 실행해, 그사이 이전 버전 앱이 만든 링크까지 채운다(#206).
--
-- - 이름은 화면 시안과 같은 "내 워크스페이스"
-- - 탈퇴한 사용자(WITHDRAWN)의 개인 워크스페이스는 DELETED로 만든다 (탈퇴 시 개인 워크스페이스를 삭제하는 규칙과 맞춤)
-- - 초대 코드는 비워 둔다. SQL로는 안전한 무작위 코드를 만들기 어려워 처음 조회할 때 앱이 발급한다
-- - 링크를 옮길 때 updated_at을 그대로 둔다 (ON UPDATE CURRENT_TIMESTAMP 때문에 모든 링크의 수정 일시가 바뀌는 것을 막는다)
--
-- 실행 계정: shortlink_migrator
-- 선행: V8__create_workspace_members.sql, V9__add_links_workspace_id.sql
-- =====================================================================

-- 1) 개인 워크스페이스 (아직 없는 사용자만)
INSERT INTO workspaces (workspace_id, name, is_personal, personal_owner_id, status, created_at)
SELECT UUID(),
       '내 워크스페이스',
       TRUE,
       u.id,
       CASE WHEN u.status = 'WITHDRAWN' THEN 'DELETED' ELSE 'ACTIVE' END,
       u.created_at
  FROM users u
 WHERE NOT EXISTS (SELECT 1 FROM workspaces w WHERE w.personal_owner_id = u.id);

-- 2) 주인을 관리자로 등록 (아직 멤버가 아닌 경우만)
INSERT INTO workspace_members (workspace_id, user_id, role, joined_at)
SELECT w.id, w.personal_owner_id, 'ADMIN', w.created_at
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
