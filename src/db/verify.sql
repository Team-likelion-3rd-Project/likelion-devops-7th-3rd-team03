-- =====================================================================
-- verify.sql
-- 마이그레이션(V1~V4)이 의도대로 적용됐는지 확인하는 조회 전용 스크립트.
--
-- 실행:
--   mysql --defaults-extra-file=~/.my.cnf shortlink < verify.sql
--   또는 mysql 접속 후  source verify.sql
--
-- 왜 마이그레이션 파일에서 분리했나:
--   1) \G 는 mysql CLI 전용 종결자다. Flyway는 JDBC로 문장을 보내므로
--      \G 를 만나면 문법 오류로 마이그레이션이 통째로 실패한다.
--   2) 마이그레이션은 "스키마를 바꾸는 문장"만 담아야 한다. 조회 구문이 섞이면
--      Flyway가 결과셋을 받아 처리해야 하고, 재실행 시 노이즈가 된다.
--   3) 검증은 반복해서 돌려보는 물건이라 마이그레이션과 생명주기가 다르다.
--      마이그레이션은 한 번 적용되면 다시 실행되지 않지만, 검증은 언제든 필요하다.
--
-- 이 파일은 스키마를 변경하지 않는다. 몇 번 실행해도 안전하다.
-- =====================================================================


-- =====================================================================
-- 1. 테이블 목록 (5개여야 정상)
-- =====================================================================
SELECT table_name, table_comment
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name <> 'schema_history'
 ORDER BY table_name;


-- =====================================================================
-- 2. V3 검증 — click_events 파티셔닝
-- =====================================================================

-- PK가 (id, clicked_at) 복합키로 바뀌었는지
-- (파티션 기준 컬럼 clicked_at 이 PK에 포함돼야 MySQL이 파티셔닝을 허용한다)
SELECT column_name, seq_in_index
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name = 'click_events'
   AND index_name = 'PRIMARY'
 ORDER BY seq_in_index;

-- 파티션별 행 수/용량. 마이그레이션 직후에는 전부 0으로 나오는 게 정상.
-- p_future 에 table_rows 가 쌓이기 시작하면 파티션 추가 배치가 밀린 것이다.
SELECT
    partition_name,
    partition_description AS upper_bound_utc,
    table_rows,
    ROUND(data_length / 1024, 1) AS data_kb
  FROM information_schema.partitions
 WHERE table_schema = DATABASE()
   AND table_name = 'click_events'
   AND partition_name IS NOT NULL
 ORDER BY partition_ordinal_position;

-- 파티션 프루닝 동작 확인.
-- partitions 컬럼에 조회 대상 파티션 하나만 찍혀야 정상 — 여러 개면 프루닝 실패.
EXPLAIN
SELECT COUNT(*) FROM click_events
 WHERE clicked_at >= '2026-08-01 00:00:00'
   AND clicked_at <  '2026-09-01 00:00:00';

-- FK가 남아 있으면 파티셔닝이 애초에 불가능하다. 결과가 0행이어야 정상.
SELECT constraint_name
  FROM information_schema.table_constraints
 WHERE table_schema = DATABASE()
   AND table_name = 'click_events'
   AND constraint_type = 'FOREIGN KEY';


-- =====================================================================
-- 3. V4 검증 — visitor_hash -> visitor_id 전환
-- =====================================================================

-- column_name = visitor_id, data_type = char, character_maximum_length = 36 이어야 정상
SELECT column_name, data_type, character_maximum_length, is_nullable
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'click_events'
   AND column_name LIKE 'visitor%';

-- 인덱스도 같이 이름이 바뀌었는지 (idx_click_events_visitor_id 하나만 나와야 정상)
SELECT index_name, column_name, seq_in_index
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name = 'click_events'
   AND index_name LIKE '%visitor%'
 ORDER BY index_name, seq_in_index;


-- =====================================================================
-- 4. V1 검증 — slug 콜레이션
-- =====================================================================
-- collation_name 이 'ascii_bin' 이어야 대소문자를 구분한다.
-- utf8mb4_0900_ai_ci 로 나오면 Ab3f2Xz 와 ab3f2xz 가 같은 값으로 취급되어
-- UNIQUE 충돌이 잦아지고 슬러그 공간이 62^7에서 36^7로 줄어든다.
SELECT column_name, character_set_name, collation_name
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name = 'links'
   AND column_name = 'slug';


-- =====================================================================
-- 5. 서버 설정 — 타임존
-- =====================================================================
-- 둘 다 +00:00 이고 NOW() = UTC_TIMESTAMP() 여야 정상.
-- 어긋나면 clicked_at(UTC 저장)과 stat_date(KST 기준일) 사이의
-- 하루 경계 변환이 조용히 틀어진다.
SELECT @@global.time_zone AS global_tz,
       @@session.time_zone AS session_tz,
       NOW() AS now_value,
       UTC_TIMESTAMP() AS utc_value;


-- =====================================================================
-- 6. 마이그레이션 적용 이력
-- =====================================================================
-- run-migration.sh 로 실행한 경우에만 존재한다.
-- Flyway 로 실행했다면 테이블명이 flyway_schema_history 다.
SELECT version, script, success, installed_at
  FROM schema_history
 ORDER BY installed_rank;


-- =====================================================================
-- 참고: 전체 DDL을 눈으로 확인하고 싶을 때
-- =====================================================================
-- 아래는 mysql CLI 에서만 동작한다(\G 는 JDBC 문법이 아니다).
-- 그래서 이 파일에서도 주석 처리해 두고, 필요할 때 직접 복사해 실행한다.
--
--   SHOW CREATE TABLE click_events\G
--   SHOW CREATE TABLE links\G
