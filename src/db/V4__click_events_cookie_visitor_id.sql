-- =====================================================================
-- V4__click_events_cookie_visitor_id.sql
-- 방문자 식별 방식을 IP+UA 해시 -> 쿠키 발급 UUID로 전환
--
-- 배경/설계 이유: README.md "9. 방문자 식별 방식: IP+UA 해시 -> 쿠키 UUID" 참조.
--
-- 실행 전 필수 확인:
--   CHAR(64) -> CHAR(36) 축소이므로, click_events에 이미 행이 있다면(로컬 테스트
--   데이터 포함) 마이그레이션이 "ERROR 1265: Data truncated"로 실패한다.
--   기존 IP+UA 해시값은 새 UUID 체계와 호환되지 않으므로 실행 전 비울 것:
--     TRUNCATE TABLE click_events;
--
-- 선행: V1__init_schema.sql, V2__drop_click_events_fk.sql, V3__partition_click_events.sql
-- 실행 계정: shortlink_migrator
-- =====================================================================

ALTER TABLE click_events
    CHANGE COLUMN visitor_hash visitor_id CHAR(36) NOT NULL
        COMMENT '방문자 식별 쿠키 값(UUID). 서버가 최초 방문 시 발급해 Set-Cookie로 내려주고, 이후 요청은 쿠키에 담긴 이 값을 그대로 저장한다.',
    RENAME INDEX idx_click_events_visitor_hash TO idx_click_events_visitor_id;


-- =====================================================================
-- 검증
-- =====================================================================
-- 검증 쿼리는 verify.sql 의 "3. V4 검증" 절로 분리했다.
--   mysql --defaults-extra-file=~/.my.cnf shortlink < verify.sql
--
-- \G 는 mysql CLI 전용 종결자라 Flyway(JDBC)에서는 문법 오류가 난다.
-- 마이그레이션에는 스키마를 바꾸는 문장만 남긴다.
--
-- verify.sql 에서 확인하는 것:
--   visitor_id 컬럼이 CHAR(36) NOT NULL 인지 /
--   인덱스가 idx_click_events_visitor_id 로 바뀌었는지
