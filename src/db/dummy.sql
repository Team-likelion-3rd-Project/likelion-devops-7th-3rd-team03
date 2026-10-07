-- =====================================================================
-- dummy-data.sql
-- 통계 API 개발/테스트용 더미 데이터
--
-- 전제: users, links 테이블에 최소 1명의 유저와 1개 이상의 링크가 이미 있어야 함.
--       아래 :userId, :linkId 자리를 실제 값으로 바꿔서 실행할 것.
--       (SELECT id FROM users LIMIT 1; / SELECT id FROM links LIMIT 1; 로 확인 가능)
-- =====================================================================

-- 예시: link_id = 1 인 링크가 있다고 가정 (실제 값으로 바꿔서 사용)

-- 1) 일별 통계 (기간별 조회, 증감률, 링크별 비교용)
-- 오늘(예: 2026-08-07) 기준 최근 5일치를 넣어서 "어제 vs 그제" 증감률도 바로 테스트 가능하게 함
INSERT INTO link_daily_stats (link_id, stat_date, click_count, visitor_count) VALUES
                                                                                  (1, '2026-08-03', 80,  65),
                                                                                  (1, '2026-08-04', 95,  70),
                                                                                  (1, '2026-08-05', 100, 80),
                                                                                  (1, '2026-08-06', 120, 95),
                                                                                  (1, '2026-08-07', 140, 110)
    ON DUPLICATE KEY UPDATE
                         click_count = VALUES(click_count),
                         visitor_count = VALUES(visitor_count);

-- 2) 유입경로 / 디바이스 / 지역 분포 (기타 분포 API용)
INSERT INTO link_daily_dimension_stats (link_id, stat_date, dimension_type, dimension_value, click_count) VALUES
-- 유입경로
(1, '2026-08-06', 'REFERRER', 'INSTAGRAM', 45),
(1, '2026-08-06', 'REFERRER', 'KAKAOTALK', 30),
(1, '2026-08-06', 'REFERRER', 'DIRECT',    25),
(1, '2026-08-06', 'REFERRER', 'ETC',       20),
-- 디바이스
(1, '2026-08-06', 'DEVICE', 'MOBILE',  70),
(1, '2026-08-06', 'DEVICE', 'DESKTOP', 45),
(1, '2026-08-06', 'DEVICE', 'TABLET',   5),
-- 지역
(1, '2026-08-06', 'REGION', 'SEOUL', 60),
(1, '2026-08-06', 'REGION', 'BUSAN', 35),
(1, '2026-08-06', 'REGION', 'ETC',   25)
    ON DUPLICATE KEY UPDATE
                         click_count = VALUES(click_count);

-- =====================================================================
-- 실시간 접속자 수는 DB가 아니라 Redis에 넣는다 (별도로 redis-cli 실행)
-- =====================================================================
-- redis-cli SET stats:2026-08-07:link:1:clicks 42
-- redis-cli PFADD stats:2026-08-07:link:1:uv visitor-1
-- (키 형식: stats:{KST date}:link:{link_id}:{clicks|uv})
