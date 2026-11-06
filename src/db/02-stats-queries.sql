-- =====================================================================
-- 02-stats-queries.sql
-- 통계 기능 3종 조회 쿼리 (FR-004-1 / FR-004-3 / FR-004-4)
-- 전제: 00-database-setup.sql, V1__init_schema.sql ~ V4__click_events_cookie_visitor_id.sql 적용 완료, MySQL 8.0 이상
--
-- 공통 규칙
--  - 모든 조회는 links JOIN으로 소유권을 검증한다. 이게 빠지면 링크 ID만 바꿔서
--    남의 통계를 볼 수 있다. 애플리케이션 필터에 의존하지 말고 쿼리에 박을 것.
--  - 조회는 집계 테이블만 본다. click_events 직접 조회는 배치 전용.
--  - 당일 수치는 아직 집계 전이라 이 쿼리들에 나오지 않는다.
--    API 레이어에서 Redis 카운터를 읽어 마지막 행에 더해줄 것.
--  - stat_date는 KST 기준일. clicked_at은 UTC 저장이므로 집계 배치에서 변환한다.
--  - [중요] 아래 모든 :linkId / :linkIds 파라미터는 links.id (내부 BIGINT)를 가리킨다.
--    API 경로변수로 오는 외부 노출용 UUID(links.link_id, Link 엔티티의 linkId 필드)와는
--    이름만 같을 뿐 다른 값이다. 리포지토리/서비스 계층에서 반드시 UUID -> 내부 id
--    변환을 거친 뒤 이 값을 바인딩해야 한다. 그대로 UUID 문자열을 넘기면 BIGINT 컬럼과
--    비교가 안 맞아 조용히 0건이 나오거나 타입 에러가 난다.
-- =====================================================================


-- =====================================================================
-- [FR-004-1] 기간별 클릭/방문자 수 분포 — 단일 링크 일별 시계열
-- 파라미터: :userId, :linkId, :from(DATE), :to(DATE)
-- =====================================================================
SELECT
    s.stat_date,
    s.click_count,
    s.visitor_count
FROM link_daily_stats s
JOIN links l
      ON l.id = s.link_id
     AND l.user_id = :userId          -- 소유권 검증
     AND l.is_visible = TRUE
WHERE s.link_id = :linkId
  AND s.stat_date BETWEEN :from AND :to
ORDER BY s.stat_date;


-- ---------------------------------------------------------------------
-- [FR-004-1 변형] 클릭이 0인 날도 0으로 채워서 반환 (차트에 구멍이 생기지 않게)
-- 집계 테이블에는 클릭이 있던 날만 행이 존재하므로 날짜 축을 따로 만들어 LEFT JOIN 한다.
-- 재귀 CTE는 MySQL 8.0 이상 필요. cte_max_recursion_depth 기본값 1000이라
-- 최대 1000일까지 안전 (365일 요구사항 충족).
-- ---------------------------------------------------------------------
WITH RECURSIVE date_axis (d) AS (
    SELECT :from
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM date_axis WHERE d < :to
)
SELECT
    a.d                                AS stat_date,
    COALESCE(s.click_count, 0)         AS click_count,
    COALESCE(s.visitor_count, 0)       AS visitor_count
FROM date_axis a
LEFT JOIN link_daily_stats s
       ON s.stat_date = a.d
      AND s.link_id = :linkId
WHERE EXISTS (                          -- 소유권 검증 (LEFT JOIN이라 별도 EXISTS로)
    SELECT 1 FROM links l
     WHERE l.id = :linkId
       AND l.user_id = :userId
       AND l.is_visible = TRUE
)
ORDER BY a.d;


-- ---------------------------------------------------------------------
-- [FR-004-1 요약] 기간 합계
-- 주의: sum_of_daily_visitors는 "일별 UV의 단순 합"이지 기간 순방문자 수가 아니다.
--       link_daily_stats.visitor_count 자체가 "그 날 하루 안에서만" 유효한 값이라,
--       같은 사람이 3일 연속 방문하면 일별 집계에서 3번 따로 카운트되어 그대로 합산된다
--       (V4 이전 IP+UA 해시 salt 일별 로테이션 때문이 아니라, 쿠키 UUID로 바뀐 지금도
--       "하루 단위 UV"라는 집계 설계 자체가 원인 — README "9. 방문자 식별 방식" 참조).
--       그래서 화면 라벨은 "순 방문자"가 아니라 "일별 방문자 합계"로 표기하기로 확정했다
--       (README 요약 표 참조. 기간 조회 상한을 두지 않기로 하면서, 진짜 기간 순방문자를
--       내려면 30일 보관 한도 내 click_events에서 COUNT(DISTINCT visitor_id) 직접 계산이
--       필요한데 비용이 커서 채택하지 않음).
-- ---------------------------------------------------------------------
SELECT
    SUM(s.click_count)                          AS total_clicks,
    SUM(s.visitor_count)                        AS sum_of_daily_visitors,
    ROUND(AVG(s.click_count), 1)                AS avg_daily_clicks,
    MAX(s.click_count)                          AS peak_clicks,
    COUNT(*)                                    AS active_days
FROM link_daily_stats s
JOIN links l
      ON l.id = s.link_id
     AND l.user_id = :userId
     AND l.is_visible = TRUE
WHERE s.link_id = :linkId
  AND s.stat_date BETWEEN :from AND :to;


-- =====================================================================
-- [FR-004-3] 링크별 클릭/방문자 수 비교 — 여러 링크 한 번에
-- 파라미터: :userId, :linkIds(IN 목록), :from, :to
--
-- IN 목록은 애플리케이션에서 바인딩한다. JPA라면 @Param List<Long>.
-- 목록이 비면 IN () 문법 오류가 나므로 서비스 레이어에서 빈 리스트를 먼저 막을 것.
-- =====================================================================
SELECT
    l.id                                        AS link_id,
    l.slug,
    l.title,
    COALESCE(SUM(s.click_count), 0)             AS total_clicks,
    COALESCE(SUM(s.visitor_count), 0)           AS sum_of_daily_visitors,
    COALESCE(MAX(s.click_count), 0)             AS peak_daily_clicks
FROM links l
LEFT JOIN link_daily_stats s
       ON s.link_id = l.id
      AND s.stat_date BETWEEN :from AND :to
WHERE l.user_id = :userId                       -- 소유권 검증
  AND l.is_visible = TRUE
  AND l.id IN (:linkIds)
GROUP BY l.id, l.slug, l.title
ORDER BY total_clicks DESC;
-- LEFT JOIN이므로 해당 기간에 클릭이 0인 링크도 0으로 나온다.
-- INNER JOIN으로 바꾸면 그 링크가 목록에서 통째로 사라져 "비교"가 깨진다.


-- ---------------------------------------------------------------------
-- [FR-004-3 변형] 사용자의 상위 N개 링크 (비교 대상 고를 때 쓰는 목록)
-- ---------------------------------------------------------------------
SELECT
    l.id                                        AS link_id,
    l.slug,
    l.title,
    COALESCE(SUM(s.click_count), 0)             AS total_clicks
FROM links l
LEFT JOIN link_daily_stats s
       ON s.link_id = l.id
      AND s.stat_date BETWEEN :from AND :to
WHERE l.user_id = :userId
  AND l.is_visible = TRUE
GROUP BY l.id, l.slug, l.title
ORDER BY total_clicks DESC
LIMIT :topN;


-- =====================================================================
-- [FR-004-4] 유입 경로 분석 — 집계 테이블 기반 (운영용, 이걸 쓸 것)
-- 파라미터: :userId, :linkId, :from, :to
-- =====================================================================
SELECT
    d.dimension_value                           AS referrer_category,
    SUM(d.click_count)                          AS click_count,
    ROUND(
        100.0 * SUM(d.click_count) / NULLIF(SUM(SUM(d.click_count)) OVER (), 0),
        1
    )                                           AS percentage
FROM link_daily_dimension_stats d
JOIN links l
      ON l.id = d.link_id
     AND l.user_id = :userId
     AND l.is_visible = TRUE
WHERE d.link_id = :linkId
  AND d.dimension_type = 'REFERRER'
  AND d.stat_date BETWEEN :from AND :to
GROUP BY d.dimension_value
ORDER BY click_count DESC;
-- NULLIF는 전체 클릭이 0일 때 0으로 나누는 것을 막는다.


-- ---------------------------------------------------------------------
-- [FR-004-4 배치] click_events -> link_daily_dimension_stats 집계 UPSERT
-- 하루 1회, 전날(KST) 기준으로 실행. 재실행해도 같은 결과가 되도록 UPSERT.
-- 파라미터: :statDate (KST 기준일, 예 '2026-08-03')
--
-- UTC로 저장된 clicked_at을 KST 하루 경계로 자른다.
-- CONVERT_TZ에 'Asia/Seoul' 같은 이름을 쓰려면 mysql.time_zone 테이블이 로드돼야 하므로
-- 항상 동작하는 '+09:00' 오프셋을 쓴다. (한국은 서머타임이 없어 오프셋 고정으로 안전)
-- ---------------------------------------------------------------------
INSERT INTO link_daily_dimension_stats
    (link_id, stat_date, dimension_type, dimension_value, click_count)
SELECT
    e.link_id,
    :statDate,
    'REFERRER',
    COALESCE(e.referrer_category, 'UNKNOWN'),
    COUNT(*)
FROM click_events e
WHERE e.is_bot = FALSE                                   -- 봇 제외 (삭제하지 않고 집계에서만 제외)
  AND e.clicked_at >= CONVERT_TZ(CONCAT(:statDate, ' 00:00:00'), '+09:00', '+00:00')
  AND e.clicked_at <  CONVERT_TZ(CONCAT(:statDate, ' 00:00:00'), '+09:00', '+00:00') + INTERVAL 1 DAY
GROUP BY e.link_id, COALESCE(e.referrer_category, 'UNKNOWN')
ON DUPLICATE KEY UPDATE
    click_count = VALUES(click_count);
-- DEVICE / REGION도 같은 형태로 dimension_type만 바꿔 실행한다.
-- REGION은 상위 N개만 남기고 나머지를 'ETC'로 합치는 후처리를 추가할 것.


-- ---------------------------------------------------------------------
-- [FR-004-1/3 배치] click_events -> link_daily_stats 집계 UPSERT
-- ---------------------------------------------------------------------
INSERT INTO link_daily_stats (link_id, stat_date, click_count, visitor_count)
SELECT
    e.link_id,
    :statDate,
    COUNT(*),
    COUNT(DISTINCT e.visitor_id)                 -- 같은 날 안에서만 유효한 UV
FROM click_events e
WHERE e.is_bot = FALSE
  AND e.clicked_at >= CONVERT_TZ(CONCAT(:statDate, ' 00:00:00'), '+09:00', '+00:00')
  AND e.clicked_at <  CONVERT_TZ(CONCAT(:statDate, ' 00:00:00'), '+09:00', '+00:00') + INTERVAL 1 DAY
GROUP BY e.link_id
ON DUPLICATE KEY UPDATE
    click_count   = VALUES(click_count),
    visitor_count = VALUES(visitor_count);


-- =====================================================================
-- [참고] 유입경로를 원본 referrer URL에서 즉석 분류하는 쿼리
-- 집계 테이블이 아직 없을 때의 임시용. 운영 API에는 쓰지 말 것.
--
-- 문제점:
--  - MySQL에는 URL 파싱 함수가 없어 SUBSTRING_INDEX를 겹쳐 써야 하고 인덱스를 못 탄다
--  - click_events는 30일만 보관하므로 그 이전 기간은 조회 자체가 불가능하다
--  - 원본 referrer는 쿼리스트링까지 달려 있어 GROUP BY 하면 값 종류가 폭발한다
-- => 그래서 수집 시점에 referrer_category로 분류해 저장하는 것이다.
-- =====================================================================
SELECT
    CASE
        WHEN r.referrer IS NULL OR r.referrer = '' THEN 'DIRECT'
        WHEN r.host LIKE '%instagram.%'             THEN 'INSTAGRAM'
        WHEN r.host LIKE '%facebook.%'              THEN 'FACEBOOK'
        WHEN r.host LIKE '%youtube.%'               THEN 'YOUTUBE'
        WHEN r.host LIKE '%naver.%'                 THEN 'NAVER'
        WHEN r.host LIKE '%google.%'                THEN 'GOOGLE'
        WHEN r.host LIKE '%daum.%' OR r.host LIKE '%kakao.%' THEN 'KAKAO'
        WHEN r.host LIKE '%t.co' OR r.host LIKE '%twitter.%' OR r.host LIKE '%x.com' THEN 'X'
        ELSE 'ETC'
    END                                          AS referrer_category,
    COUNT(*)                                     AS click_count
FROM (
    SELECT
        e.referrer,
        -- https://www.example.com:443/path?a=1 -> www.example.com
        LOWER(
            SUBSTRING_INDEX(
                SUBSTRING_INDEX(
                    SUBSTRING_INDEX(
                        REPLACE(REPLACE(e.referrer, 'https://', ''), 'http://', ''),
                    '/', 1),
                '?', 1),
            ':', 1)
        ) AS host
    FROM click_events e
    WHERE e.link_id = :linkId
      AND e.is_bot = FALSE
      AND e.clicked_at >= CONVERT_TZ(CONCAT(:from, ' 00:00:00'), '+09:00', '+00:00')
      AND e.clicked_at <  CONVERT_TZ(CONCAT(:to,   ' 00:00:00'), '+09:00', '+00:00') + INTERVAL 1 DAY
) AS r
GROUP BY referrer_category
ORDER BY click_count DESC;
-- 'www.' 접두어는 위 LIKE '%...%' 패턴이 흡수한다.
-- 이 CASE 로직을 그대로 애플리케이션(수집 시점)으로 옮겨 referrer_category에 저장할 것.
