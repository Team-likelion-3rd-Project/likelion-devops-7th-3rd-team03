-- =====================================================================
-- 00-database-setup.sql
-- 데이터베이스 · 계정 · 권한 초기 세팅
--
-- 실행 주체: RDS 마스터 계정 (또는 로컬 root). 최초 1회만.
-- 실행 순서: 00-database-setup.sql -> V1__init_schema.sql -> V2__drop_click_events_fk.sql
--          -> V3__partition_click_events.sql -> V4__click_events_cookie_visitor_id.sql -> (배치/조회)
-- run-migration.sh가 00 실행 후 V*.sql을 버전 순서대로 자동 적용한다.
--
-- [중요] 이 파일에 실제 비밀번호를 적어 커밋하지 말 것.
--        '<CHANGE_ME_...>' 부분을 배포 시점에 치환하거나
--        AWS Secrets Manager에서 주입받아 실행한다.
--        Terraform으로 RDS를 만든다면 random_password + aws_secretsmanager_secret로
--        생성하고, 이 스크립트는 그 값을 받아 실행하는 형태가 된다.
--
-- [중요] DB 이름도 비밀번호와 동일하게 플레이스홀더('<CHANGE_ME_DB_NAME>')로 뒀다.
--        예전엔 'shortlink'를 리터럴로 박아뒀는데, run-migration.sh의 DB_NAME
--        환경변수를 shortlink 외의 값으로 바꾸면 이 파일은 여전히 'shortlink'라는
--        DB를 만들고 그 DB에만 권한을 부여해서, 뒤이은 V*.sql 적용 단계가
--        DB_NAME으로 접속을 시도하다 "Unknown database" 에러로 조용히 실패했다.
--        run-migration.sh가 비밀번호 3개와 함께 이 플레이스홀더도 sed로 치환한다.
-- =====================================================================


-- =====================================================================
-- 1. 데이터베이스 생성
-- =====================================================================
-- utf8mb4: 이모지 등 4바이트 문자 저장 가능. utf8(=utf8mb3)은 이모지가 깨진다.
-- utf8mb4_0900_ai_ci: MySQL 8.0 기본 collation. 대소문자/악센트를 무시하고 비교한다.
--   -> slug처럼 대소문자를 구분해야 하는 컬럼은 컬럼 레벨에서 따로 지정해야 한다.
--      (CHARACTER SET ascii COLLATE ascii_bin)
CREATE DATABASE IF NOT EXISTS <CHANGE_ME_DB_NAME>
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE <CHANGE_ME_DB_NAME>;


-- =====================================================================
-- 2. 계정 생성 — 역할별로 분리한다
-- =====================================================================
-- 애플리케이션 계정 하나로 DDL까지 다 하는 구성은 실무에서 쓰지 않는다.
-- 애플리케이션이 탈취되면 DROP TABLE까지 가능해지기 때문이다.
--
-- 호스트를 '%'로 둔 이유: RDS는 VPC 보안 그룹으로 접근을 통제하므로
-- MySQL 계정 레벨에서 IP를 제한하는 실익이 적다. 대신 보안 그룹을 반드시 좁힐 것.
-- 로컬/온프레미스라면 'app-server-ip'처럼 구체적으로 지정하는 편이 낫다.

-- (1) 애플리케이션 계정 — 평상시 서비스가 쓰는 계정. DML만 가능.
CREATE USER IF NOT EXISTS 'shortlink_app'@'%'
    IDENTIFIED BY '<CHANGE_ME_APP_PASSWORD>';

-- (2) 마이그레이션 계정 — Flyway/Liquibase 전용. DDL 포함.
CREATE USER IF NOT EXISTS 'shortlink_migrator'@'%'
    IDENTIFIED BY '<CHANGE_ME_MIGRATOR_PASSWORD>';

-- (3) 읽기 전용 계정 — 모니터링, 지표 확인, 장애 조사용.
CREATE USER IF NOT EXISTS 'shortlink_readonly'@'%'
    IDENTIFIED BY '<CHANGE_ME_READONLY_PASSWORD>';


-- =====================================================================
-- 3. 권한 부여
-- =====================================================================

-- (1) 애플리케이션 계정: DML만. DDL 없음.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON <CHANGE_ME_DB_NAME>.*
    TO 'shortlink_app'@'%';

-- (2) 마이그레이션 계정: 스키마를 만들고 바꾸는 데 필요한 것만.
--     REFERENCES : FOREIGN KEY 생성에 필요
--     ALTER      : 파티션 추가(REORGANIZE PARTITION)에 필요
--     DROP       : 파티션 DROP 및 롤백에 필요
--     INDEX      : CREATE INDEX / DROP INDEX
--
--     EVENT는 의도적으로 제외했다. 파티션 자동 생성/삭제는 MySQL 내장 이벤트
--     스케줄러(CREATE EVENT)가 아니라 K8s CronJob이 shortlink_migrator 계정으로
--     외부 접속해 ALTER/DROP을 직접 실행하는 방식으로 정했으므로(V3 참조),
--     DB 안에서 주기적으로 SQL을 실행할 수 있는 EVENT 권한 자체가 불필요하다 —
--     최소 권한 원칙상 쓰지 않는 권한은 애초에 부여하지 않는다.
GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, DROP, INDEX, REFERENCES
    ON <CHANGE_ME_DB_NAME>.*
    TO 'shortlink_migrator'@'%';

-- (3) 읽기 전용 계정
GRANT SELECT
    ON <CHANGE_ME_DB_NAME>.*
    TO 'shortlink_readonly'@'%';

-- SHOW PROCESSLIST로 다른 세션의 슬로우 쿼리를 보려면 전역 PROCESS 권한이 필요하다.
-- RDS 마스터 계정도 SUPER는 가질 수 없으므로 이 정도가 현실적인 상한이다.
GRANT PROCESS ON *.* TO 'shortlink_readonly'@'%';

FLUSH PRIVILEGES;


-- =====================================================================
-- 4. 서버 설정 확인
-- =====================================================================
-- 시각 처리를 UTC로 통일한다. clicked_at을 UTC로 저장하고 KST 변환은
-- 조회/집계 시점에 하므로, 서버 타임존이 OS를 따라가면 집계 경계가 어긋난다.
--
-- 로컬(도커): compose에 --default-time-zone=+00:00
-- RDS: 파라미터 그룹에서 time_zone = UTC
--      (RDS 기본값이 UTC지만 파라미터 그룹을 복사해 쓰다 보면 바뀌어 있는 경우가 있다)

-- 둘 다 +00:00 이고 NOW() = UTC_TIMESTAMP() 여야 정상
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

-- 데이터베이스 charset/collation 확인
SELECT schema_name, default_character_set_name, default_collation_name
  FROM information_schema.schemata
 WHERE schema_name = '<CHANGE_ME_DB_NAME>';

-- 권한이 의도대로 들어갔는지 확인
SHOW GRANTS FOR 'shortlink_app'@'%';
SHOW GRANTS FOR 'shortlink_migrator'@'%';
SHOW GRANTS FOR 'shortlink_readonly'@'%';


-- =====================================================================
-- 참고: 애플리케이션 접속 설정 (application.yml)
-- =====================================================================
-- spring:
--   datasource:
--     url: jdbc:mysql://<host>:3306/<db-name>?serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true
--          # <db-name> 자리는 위에서 실제로 치환한 DB_NAME 값(기본값은 shortlink)과 일치해야 한다.
--     username: shortlink_app
--     password: ${DB_PASSWORD}              # 환경변수 / Secrets Manager로 주입
--   flyway:
--     user: shortlink_migrator              # 마이그레이션만 별도 계정
--     password: ${DB_MIGRATOR_PASSWORD}
--   jpa:
--     hibernate:
--       ddl-auto: validate                  # create/update로 두지 말 것.
--                                           # 파티션 DDL은 Hibernate가 관리할 수 없다.
--
-- rewriteBatchedStatements=true : 집계 배치의 대량 INSERT/UPSERT 성능 차이가 크다.
--                                 이 옵션이 없으면 JDBC 배치가 실제로는 한 건씩 나간다.


-- =====================================================================
-- 참고: 롤백 / 재실행용
-- =====================================================================
-- DROP USER IF EXISTS 'shortlink_app'@'%';
-- DROP USER IF EXISTS 'shortlink_migrator'@'%';
-- DROP USER IF EXISTS 'shortlink_readonly'@'%';
-- DROP DATABASE IF EXISTS <CHANGE_ME_DB_NAME>;
