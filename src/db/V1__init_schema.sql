-- =====================================================================
-- V1__init_schema.sql
-- 초기 테이블 스키마
--
-- 설계 문서 명세를 기반으로 하되, README "알려진 제약사항" 중 아직 데이터가
-- 없어 지금 고치는 게 무료인 것들은 여기 V1에 바로 반영했다.
-- (아래 번호는 README.md의 "## N." 섹션 번호를 그대로 가리킨다. 예전엔 이 번호가
--  README 재구성 전 초안 순서를 따르고 있어서 실제 README.md 번호와 어긋나
--  있었다 — 지금은 현재 README.md 기준으로 다시 맞춘 것이다.)
--   §3 slug ascii_bin 콜레이션 적용 (대소문자 구분)
--   §2 TIMESTAMP -> DATETIME 전환 (2038년 상한 회피, 파티셔닝 선행조건)
--   §1 link_daily_dimension_stats 테이블 신규 추가 (소급 불가라 오픈 전 필수)
--   clicked_at NOT NULL 명시 (README에 독립 섹션은 없음 — explicit_defaults_for_timestamp=ON인
--                            MySQL 8.0에서 NOT NULL을 빼면 조용히 NULL을 허용하는 문제 대응용
--                            구현 세부사항. §2 TIMESTAMP->DATETIME 전환과 같이 묶어서 봐도 된다)
--   §6 idx_users_kakao_id 중복 인덱스 제거
--   (버그) click_events.referrer_category 컬럼 추가 — 02-stats-queries.sql이 참조하는데
--          V1 초안 스키마엔 없어서 배치 실행 시 ERROR 1054로 죽던 문제
--
-- 아직 반영하지 않은 것 (팀 결정 대기 — README "미결 사항" 참조):
--   - 서비스 경계 FK 유지 여부 (§7 관련, V2에서 최종 결정) : FK 3개 그대로 둠
--   - click_events 파티셔닝 (§8) : FK 제거 여부가 먼저 정해져야 진행 가능
--   - 슬러그 대소문자 정책 자체(생성 규칙) : 콜레이션만 미리 적용, 정책은 링크 서비스 담당자 결정 필요
--
-- 실행 계정: shortlink_migrator
-- 선행: 00-database-setup.sql (DB / 계정 / 권한 생성)
--
-- 설계 배경, 서비스 경계와 FK 문제, 알려진 제약사항은 README.md 참조.
-- =====================================================================


-- =====================================================================
-- 1. users (사용자)
-- =====================================================================
CREATE TABLE users (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '내부 식별자 (조인용)',
    user_id           VARCHAR(36)  NOT NULL COMMENT '외부 노출용 사용자 식별자 (UUID)',
    kakao_id          BIGINT       NOT NULL COMMENT '카카오 고유 회원번호',
    nickname          VARCHAR(50)  NULL COMMENT '카카오 닉네임 (선택 동의항목, NULL 가능)',
    profile_image_url VARCHAR(500) NULL COMMENT '카카오 프로필 이미지 URL (선택 동의항목)',
    email             VARCHAR(100) NULL COMMENT '카카오 이메일 (선택 동의항목, NULL 가능)',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '계정 상태 (ACTIVE/WITHDRAWN)',
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '가입(최초 로그인) 일시',
    updated_at        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '정보 수정 일시',
    -- 이름을 명시하지 않으면 MySQL이 컬럼명 그대로 제약명을 붙여 User.java의
    -- @UniqueConstraint(name = "uk_users_user_id" 등)와 어긋난다. 엔티티와 정확히 맞춘다.
    CONSTRAINT uk_users_user_id UNIQUE (user_id),
    CONSTRAINT uk_users_kakao_id UNIQUE (kakao_id)
) COMMENT '사용자 기본 정보 테이블';

-- kakao_id는 UNIQUE 제약으로 인덱스가 이미 자동 생성되므로 별도 인덱스를 만들지 않는다.
-- (README "알려진 제약사항 #8" 반영 — 중복 인덱스 제거)
CREATE INDEX idx_users_created_at ON users(created_at);


-- =====================================================================
-- 2. links (링크)
-- =====================================================================
CREATE TABLE links (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '내부 식별자',
    link_id       VARCHAR(36)   NOT NULL COMMENT '외부 노출용 링크 식별자 (UUID)',
    user_id       BIGINT        NOT NULL COMMENT '소유자 사용자 ID (내부 FK)',
    -- ascii_bin: 테이블 기본 collation(utf8mb4_0900_ai_ci)은 대소문자를 무시해
    -- Ab3f2Xz / ab3f2xz 가 같은 값으로 취급되어 UNIQUE 충돌이 잦아진다 (README 제약사항 #1).
    -- 슬러그를 소문자 전용으로만 생성하기로 정하더라도 이 설정은 무해하므로,
    -- 데이터가 없는 지금 미리 적용해둔다 (데이터가 쌓인 뒤 ALTER하면 비용이 커짐).
    slug          VARCHAR(20)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '단축 슬러그 (리다이렉트 경로, 대소문자 구분)',
    title         VARCHAR(100)  NULL COMMENT '제목',
    original_url  VARCHAR(2048) NOT NULL COMMENT '원본 URL',
    is_visible    BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '노출 여부 (soft-delete 플래그)',
    expires_at    DATETIME      NULL COMMENT '만료일시 (NULL이면 무기한)',
    created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '링크 생성일시',
    updated_at    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '링크 수정일시',
    FOREIGN KEY (user_id) REFERENCES users(id),
    -- 이름을 명시하지 않으면 MySQL이 컬럼명 그대로 제약명을 붙여 Link.java의
    -- @UniqueConstraint(name = "uk_links_link_id" 등)와 어긋난다. 엔티티와 정확히 맞춘다.
    CONSTRAINT uk_links_link_id UNIQUE (link_id),
    CONSTRAINT uk_links_slug UNIQUE (slug),
    CONSTRAINT chk_slug_length CHECK (CHAR_LENGTH(slug) BETWEEN 4 AND 20)
) COMMENT '단축 링크 정보 테이블';

CREATE INDEX idx_links_user_visible ON links(user_id, is_visible);
CREATE INDEX idx_links_slug_visible ON links(slug, is_visible);
CREATE INDEX idx_links_expires_at ON links(expires_at);


-- =====================================================================
-- 3. click_events (클릭 원본 로그)
-- =====================================================================
CREATE TABLE click_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '클릭 이벤트 식별자',
    link_id      BIGINT       NOT NULL COMMENT '클릭된 링크 ID (내부 FK)',
    -- NOT NULL 명시 (README 제약사항 #7): explicit_defaults_for_timestamp=ON인 MySQL 8.0에서는
    -- NOT NULL을 빼면 TIMESTAMP/DATETIME 컬럼이 조용히 NULL을 허용한다.
    clicked_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '클릭 발생 시각 (UTC)',
    visitor_hash CHAR(64)     NOT NULL COMMENT 'SHA256(원본IP + UserAgent), UV 판별용. 원본 IP는 저장하지 않음',
    ip_masked    VARCHAR(45)  NULL COMMENT '마지막 옥텟 마스킹된 IP (예: 123.45.67.xxx), 표시/디버깅용',
    user_agent   VARCHAR(500) NULL COMMENT '요청 User-Agent (헤더 누락 시 NULL)',
    referrer     VARCHAR(500) NULL COMMENT '유입 경로 Referer 원문 (직접 유입/인앱 브라우저 시 NULL 흔함)',
    -- 02-stats-queries.sql의 [FR-004-4] 배치가 참조하는 컬럼인데 V1 초안에는 빠져 있었다.
    -- 애플리케이션이 수집 시점에 referrer 원문을 도메인 기준으로 분류해서 채운다
    -- (INSTAGRAM/FACEBOOK/... /DIRECT/ETC). 조회 시점에 매번 파싱하면 인덱스를 못 타므로
    -- 반드시 수집 시점에 채워야 한다 — 02-stats-queries.sql 하단 "참고용" 쿼리의 CASE 로직 참조.
    referrer_category VARCHAR(30) NULL COMMENT '수집 시점에 분류된 유입경로 카테고리, 미분류/NULL이면 애플리케이션이 채우지 않은 것',
    device_type  VARCHAR(20)  NULL COMMENT 'User-Agent 파싱 기반 기기 유형 (파싱 실패 시 NULL)',
    region       VARCHAR(100) NULL COMMENT 'IP 기반 추정 지역 (매핑 실패 시 NULL)',
    is_bot       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'User-Agent 기반 봇/크롤러 여부 판별. 삭제하지 않고 플래그로만 표시',
    FOREIGN KEY (link_id) REFERENCES links(id)
) COMMENT '클릭 원본 이벤트 로그 테이블 (30일 보관 후 S3 export, RDB에서는 삭제 — README §5 참조)';

CREATE INDEX idx_click_events_link_time ON click_events(link_id, clicked_at);
CREATE INDEX idx_click_events_clicked_at ON click_events(clicked_at);
CREATE INDEX idx_click_events_visitor_hash ON click_events(link_id, visitor_hash);
CREATE INDEX idx_click_events_is_bot ON click_events(link_id, is_bot);


-- =====================================================================
-- 4. link_daily_stats (일별 통계 집계)
-- =====================================================================
CREATE TABLE link_daily_stats (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '집계 row 식별자',
    link_id       BIGINT   NOT NULL COMMENT '집계 대상 링크 ID (내부 FK)',
    stat_date     DATE     NOT NULL COMMENT '집계 기준일',
    click_count   INT      NOT NULL DEFAULT 0 COMMENT '해당일 클릭 수',
    visitor_count INT      NOT NULL DEFAULT 0 COMMENT '해당일 순 방문자 수 (UV)',
    FOREIGN KEY (link_id) REFERENCES links(id),
    UNIQUE KEY uq_link_date (link_id, stat_date),
    CONSTRAINT chk_click_count CHECK (click_count >= 0),
    CONSTRAINT chk_visitor_count CHECK (visitor_count >= 0)
) COMMENT '링크별 일별 클릭/방문자 통계 집계 테이블';

CREATE INDEX idx_daily_stats_link_date ON link_daily_stats(link_id, stat_date);

-- 커버링 인덱스 (통계 조회 시 click_count, visitor_count까지 인덱스로 커버)
CREATE INDEX idx_daily_stats_covering ON link_daily_stats(link_id, stat_date, click_count, visitor_count);


-- =====================================================================
-- 5. link_daily_dimension_stats (유입경로/기기/지역 일별 차원 집계)
-- =====================================================================
-- "모든 통계 API는 link_daily_stats만 참조" 원칙과 FR-004-4/5(유입 경로,
-- 기기·지역 분포)가 충돌해서 추가한 테이블 (README 제약사항 #5).
-- click_events는 30일만 보관하므로 이 테이블 없이 나중에 소급 집계할 수 없다.
-- 그래서 다른 알려진 이슈들과 달리 "오픈 전 필수"로 지금 함께 만든다.
CREATE TABLE link_daily_dimension_stats (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '집계 row 식별자',
    link_id         BIGINT       NOT NULL COMMENT '집계 대상 링크 ID (내부 FK)',
    stat_date       DATE         NOT NULL COMMENT '집계 기준일 (KST)',
    dimension_type  VARCHAR(20)  NOT NULL COMMENT 'REFERRER / DEVICE / REGION',
    dimension_value VARCHAR(100) NOT NULL COMMENT '차원 값 (원본이 NULL이면 UNKNOWN, REGION은 상위 N개 외 ETC)',
    -- link_daily_stats.click_count와 동일하게 INT. 차원별 클릭 수는 같은 링크의
    -- 같은 날짜 총 클릭 수(link_daily_stats.click_count, INT)를 절대 넘을 수 없으므로
    -- BIGINT로 둘 이유가 없다 (예전엔 두 테이블의 click_count 타입이 서로 달라
    -- 근거 없이 불일치했다).
    click_count     INT          NOT NULL DEFAULT 0 COMMENT '해당 차원 값의 클릭 수',
    FOREIGN KEY (link_id) REFERENCES links(id),
    UNIQUE KEY uk_link_daily_dimension (link_id, stat_date, dimension_type, dimension_value),
    CONSTRAINT chk_dim_click_count CHECK (click_count >= 0)
) COMMENT '링크별 일별 차원(유입경로/기기/지역) 분포 집계 테이블';

CREATE INDEX idx_dim_stats_link_date_type ON link_daily_dimension_stats(link_id, stat_date, dimension_type);
