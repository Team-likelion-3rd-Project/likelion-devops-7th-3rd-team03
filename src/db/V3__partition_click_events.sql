-- =====================================================================
-- V3__partition_click_events.sql
-- click_events PK 변경 + 월별 RANGE 파티셔닝 적용
--
-- 배경 (README "8. 파티셔닝: 선행조건 정리 중" 참조):
--   MySQL 파티셔닝은 세 조건을 모두 만족해야 한다.
--     ① DATETIME 전환      -> V1에서 완료 (TIMESTAMP였다면 UNIX_TIMESTAMP()로
--                              감싸야만 파티션 표현식에 쓸 수 있어 번거로웠다)
--     ② FK 제거            -> V2에서 완료 (파티션된 InnoDB 테이블은 FK를 허용하지 않음)
--     ③ PK에 파티션 기준 컬럼 포함 -> 이번 V3에서 처리
--
--   MySQL 규칙: 파티션 기준 컬럼(clicked_at)은 테이블의 모든 UNIQUE 키(PK 포함)에
--   포함되어 있어야 한다. 지금 PK는 id 하나뿐이라 이 규칙을 위반한다.
--   그대로 PARTITION BY를 시도하면 다음 에러가 난다:
--     ERROR 1503: A PRIMARY KEY must include all columns in the table's
--                 partitioning function
--
-- 이 마이그레이션이 하는 일 / 안 하는 일:
--   하는 것 : PK를 (id, clicked_at) 복합키로 변경, 월별 RANGE 파티션 정의,
--             현재+향후 몇 개월치 파티션을 미리 생성
--   안 하는 것 : 파티션 자동 생성(다음 달 파티션을 매달 미리 만들어두는 것)은
--             K8s CronJob으로 별도 자동화할 예정(기존 EventBridge+Lambda 계획에서 전환 —
--             서비스가 이미 K8s 위에서 돌아가므로 별도 서버리스 배포 파이프라인 없이
--             기존 클러스터의 Secret/네트워크 경로를 그대로 쓸 수 있다는 이유) — 이 파일은
--             수동으로 만든 초기 파티션 세트만 담는다. 30일 초과 데이터의 DROP PARTITION
--             자동 삭제 배치도 별도 구현 필요(README #8과 동일하게 여기서도 보류).
--
-- id가 여전히 유일한 이유:
--   AUTO_INCREMENT인 id 자체가 이미 겹칠 일이 없으므로, clicked_at을 PK에
--   추가해도 실질적인 유일성 판단 기준은 바뀌지 않는다. 조회 코드에서
--   `WHERE id = ?`로 단건 조회하던 부분도 그대로 동작한다(id가 여전히 PK
--   왼쪽 컬럼이라 인덱스를 그대로 탄다).
--
-- 선행: V1__init_schema.sql, V2__drop_click_events_fk.sql
-- 실행 계정: shortlink_migrator
-- =====================================================================


-- =====================================================================
-- 1. PK 변경 — id 단일 PK -> (id, clicked_at) 복합 PK
-- =====================================================================
-- id가 AUTO_INCREMENT이므로 복합키의 첫 번째 컬럼으로 유지해야 한다.
-- (InnoDB 규칙: AUTO_INCREMENT 컬럼은 어떤 키든 그 키의 첫 번째 컬럼이어야 함)
ALTER TABLE click_events
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id, clicked_at);

-- 기존 보조 인덱스(idx_click_events_link_time 등)는 이 변경과 무관하게 그대로 유효하다.
-- InnoDB는 파티션 테이블에서도 "로컬 파티션 인덱스"만 지원하므로(전역 인덱스 없음),
-- 별도 인덱스 재정의가 필요 없다.


-- =====================================================================
-- 2. 월별 RANGE 파티셔닝 적용
-- =====================================================================
-- RANGE COLUMNS(clicked_at)를 쓰는 이유:
--   TIMESTAMP였다면 UNIX_TIMESTAMP()로 감싸야 했지만(README #2), V1에서 이미
--   DATETIME으로 전환해뒀으므로 clicked_at 값을 별도 함수 변환 없이 그대로
--   파티션 기준으로 쓸 수 있다.
--
-- 파티션 경계는 UTC 기준 월 단위다. clicked_at 자체가 UTC로 저장되므로
-- (00-database-setup.sql / run-migration.sh에서 검증한 time_zone=UTC 전제),
-- 여기 적힌 날짜도 전부 UTC 기준 월 경계다.
--
-- 이 시점(2026-08) 기준으로 최근 몇 개월 + 향후 여유분을 미리 만들어둔다.
-- 이후 달은 K8s CronJob 자동화가 매달 하나씩 추가하는 것을 목표로 한다.
-- CronJob은 shortlink_migrator 계정(REORGANIZE PARTITION에 필요한 ALTER 권한 보유)으로
-- 접속해 아래 예시와 동일한 REORGANIZE PARTITION 구문을 실행한다 — MySQL 내장
-- 이벤트 스케줄러(CREATE EVENT)는 쓰지 않으므로 EVENT 권한은 필요 없다.
-- 그 자동화가 붙기 전까지는, 매달 이 아래에 파티션을 수동으로 추가하거나
-- p_future(MAXVALUE)가 그 달의 데이터를 임시로 받아둔다.
ALTER TABLE click_events
PARTITION BY RANGE COLUMNS (clicked_at) (
    PARTITION p_2026_06 VALUES LESS THAN ('2026-07-01 00:00:00'),
    PARTITION p_2026_07 VALUES LESS THAN ('2026-08-01 00:00:00'),
    PARTITION p_2026_08 VALUES LESS THAN ('2026-09-01 00:00:00'),
    PARTITION p_2026_09 VALUES LESS THAN ('2026-10-01 00:00:00'),
    PARTITION p_2026_10 VALUES LESS THAN ('2026-11-01 00:00:00'),
    PARTITION p_2026_11 VALUES LESS THAN ('2026-12-01 00:00:00'),
    PARTITION p_future  VALUES LESS THAN (MAXVALUE)
);
-- p_future에 데이터가 쌓이기 시작하면(=자동화가 아직 안 붙었는데 12월이 됐다면)
-- REORGANIZE PARTITION으로 p_future를 쪼개서 새 달 파티션을 만들어야 한다.
-- 예시 (12월 파티션을 p_future에서 분리):
--   ALTER TABLE click_events REORGANIZE PARTITION p_future INTO (
--       PARTITION p_2026_12 VALUES LESS THAN ('2027-01-01 00:00:00'),
--       PARTITION p_future  VALUES LESS THAN (MAXVALUE)
--   );


-- =====================================================================
-- 3. 검증
-- =====================================================================
-- 검증 쿼리는 verify.sql 로 분리했다.
--   mysql --defaults-extra-file=~/.my.cnf shortlink < verify.sql
--
-- 마이그레이션 파일에서 뺀 이유:
--   - SHOW CREATE TABLE ... \G 의 \G 는 mysql CLI 전용 종결자다.
--     Flyway는 JDBC로 문장을 보내므로 \G 를 만나면 문법 오류가 나고
--     이 마이그레이션 전체가 실패로 기록된다.
--   - 마이그레이션에는 스키마를 바꾸는 문장만 담는 것이 원칙이다.
--     검증은 언제든 반복 실행하는 물건이라 생명주기가 다르다.
--
-- verify.sql 의 "2. V3 검증" 절에서 확인하는 것:
--   PK가 (id, clicked_at) 복합키인지 / 파티션 목록과 행 수 /
--   파티션 프루닝 동작 / FK가 남아 있지 않은지


-- =====================================================================
-- 참고: 30일 초과 데이터 자동 삭제 (아직 미구현 — 다음 단계)
-- =====================================================================
-- 파티션 단위 DROP은 DELETE보다 훨씬 가볍다(로그 최소화, 즉시 공간 반환).
-- 이 V3에서는 파티션 구조만 만들고, 실제 자동 삭제 스케줄러는 별도로 구현한다.
-- 예시 (한 달이 통째로 30일보다 오래됐을 때 그 파티션을 통째로 버림):
--   ALTER TABLE click_events DROP PARTITION p_2026_06;
-- 이 작업은 "S3 export가 끝난 뒤에만" 실행되어야 하므로, export 배치와
-- 순서를 맞추는 별도 오케스트레이션이 필요하다 (README #5 참조).
