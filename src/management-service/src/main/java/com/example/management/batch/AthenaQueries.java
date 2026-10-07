package com.example.management.batch;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * click_events(Glue) 테이블 기준 집계 쿼리.
 *
 * S3 파티션(year/month/day)은 Firehose 기본 동작상 UTC 도착 시각 기준이라,
 * KST 하루치를 온전히 커버하려면 대상일 기준 UTC 파티션 2개(전날+당일)를 훑어야 한다.
 * 실제 날짜 필터는 clicked_at을 KST로 변환한 값으로 다시 거른다.
 *
 * is_bot=true는 모든 집계에서 제외한다 (LinkDailyStat.clickCount 주석 참고).
 */
final class AthenaQueries {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private AthenaQueries() {
    }

    static String dailyStats(LocalDate targetDateKst) {
        LocalDate prevUtcPartition = targetDateKst.minusDays(1);

        return """
                SELECT
                  link_id,
                  CAST(stat_date AS VARCHAR) AS stat_date,
                  COUNT(*) AS click_count,
                  APPROX_DISTINCT(visitor_id) AS visitor_count
                FROM (
                  SELECT
                    link_id,
                    visitor_id,
                    DATE(from_iso8601_timestamp(clicked_at) AT TIME ZONE 'Asia/Seoul') AS stat_date
                  FROM click_events
                  WHERE %s
                    AND is_bot = false
                ) t
                WHERE stat_date = DATE '%s'
                GROUP BY link_id, stat_date
                """.formatted(
                partitionFilter(prevUtcPartition, targetDateKst),
                targetDateKst.format(YMD)
        );
    }

    static String dimensionStats(LocalDate targetDateKst, String dimensionType) {
        LocalDate prevUtcPartition = targetDateKst.minusDays(1);
        String dimensionColumn = dimensionColumn(dimensionType);

        return """
                SELECT
                  link_id,
                  CAST(stat_date AS VARCHAR) AS stat_date,
                  dimension_value,
                  COUNT(*) AS click_count
                FROM (
                  SELECT
                    link_id,
                    DATE(from_iso8601_timestamp(clicked_at) AT TIME ZONE 'Asia/Seoul') AS stat_date,
                    COALESCE(%s, 'UNKNOWN') AS dimension_value
                  FROM click_events
                  WHERE %s
                    AND is_bot = false
                ) t
                WHERE stat_date = DATE '%s'
                GROUP BY link_id, stat_date, dimension_value
                """.formatted(
                dimensionColumn,
                partitionFilter(prevUtcPartition, targetDateKst),
                targetDateKst.format(YMD)
        );
    }

    /**
     * REGION은 client_ip 제거 이후 정식 컬럼이 없어 language의 국가 서브태그를
     * 임시 프록시로 쓴다 (예: "ko-KR" -> "KR"). 정교한 GeoIP 매핑은 우선순위에서 미룬 상태.
     */
    private static String dimensionColumn(String dimensionType) {
        return switch (dimensionType) {
            case "REFERRER" -> "referrer_category";
            case "DEVICE" -> "device_type";
            case "REGION" -> "split_part(language, '-', 2)";
            default -> throw new IllegalArgumentException("unknown dimensionType: " + dimensionType);
        };
    }

    private static String partitionFilter(LocalDate prevUtc, LocalDate currentUtc) {
        return "(%s) OR (%s)".formatted(partitionEquals(prevUtc), partitionEquals(currentUtc));
    }

    private static String partitionEquals(LocalDate date) {
        return "(year='%04d' AND month='%02d' AND day='%02d')"
                .formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
