package com.example.management.batch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
 *
 * AWS 환경 전용 SQL 제공자. app.batch.engine=athena(기본값)일 때만 등록된다.
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = "app.batch.engine", havingValue = "athena", matchIfMissing = true)
final class AthenaQueries implements ClickStatsQueries {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String dailyStats(LocalDate targetDateKst) {
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

    @Override
    public String dimensionStats(LocalDate targetDateKst, String dimensionType) {
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
     * COUNTRY 는 CloudFront 위치 헤더(#207)를 쓰고, 없으면(스키마 1 이벤트) 언어 태그의 국가 서브태그로 대체한다.
     * REGION 은 #208 부터 CloudFront 시·도 코드다 (그 전엔 언어 태그 국가를 프록시로 썼다).
     */
    private static String dimensionColumn(String dimensionType) {
        return switch (dimensionType) {
            case "REFERRER" -> "referrer_category";
            case "DEVICE" -> "device_type";
            case "OS" -> "operating_system";
            case "BROWSER" -> "browser";
            case "HOUR" -> "lpad(CAST(hour(from_iso8601_timestamp(clicked_at) AT TIME ZONE 'Asia/Seoul') AS varchar), 2, '0')";
            case "COUNTRY" -> "COALESCE(NULLIF(country, ''), NULLIF(upper(split_part(language, '-', 2)), ''))";
            case "REGION" -> "CASE WHEN NULLIF(region, '') IS NULL THEN NULL ELSE concat(country, '-', region) END";
            default -> throw new IllegalArgumentException("unknown dimensionType: " + dimensionType);
        };
    }

    /**
     * 파티션 조건 전체를 괄호로 한 번 더 감싼다.
     * 감싸지 않으면 뒤에 붙는 "AND is_bot = false"가 OR보다 먼저 결합되어
     * 전날 파티션에는 봇 필터가 적용되지 않는다.
     */
    private static String partitionFilter(LocalDate prevUtc, LocalDate currentUtc) {
        return "((%s) OR (%s))".formatted(partitionEquals(prevUtc), partitionEquals(currentUtc));
    }

    private static String partitionEquals(LocalDate date) {
        return "(year='%04d' AND month='%02d' AND day='%02d')"
                .formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}