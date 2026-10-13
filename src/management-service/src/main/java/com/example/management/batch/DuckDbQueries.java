package com.example.management.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 온프렘(DuckDB) 집계 쿼리. app.batch.engine=duckdb일 때만 등록된다.
 * AthenaQueries와 같은 컬럼 순서로 결과를 내서 DailyStatRow / DimensionStatRow 변환을 그대로 재사용한다.
 *
 * Fluent Bit이 SeaweedFS에 year=/month=/day=(UTC) 경로로 저장한 줄단위 JSON(gzip)을 직접 읽는다.
 * KST 하루치를 온전히 커버하려면 UTC 파티션 2개(전날+당일)를 읽고, clicked_at을 KST로 바꿔 다시 거른다.
 *
 * Athena 쿼리와의 차이:
 *  - KST 변환은 +9시간 고정 (한국은 서머타임이 없어 타임존 확장 없이 동일한 결과)
 *  - 방문자 수는 근사치(APPROX_DISTINCT) 대신 정확한 COUNT(DISTINCT)
 *  - split_part가 값이 없을 때 빈 문자열을 주므로 NULLIF로 NULL 처리해 UNKNOWN으로 묶는다
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = "app.batch.engine", havingValue = "duckdb")
final class DuckDbQueries implements ClickStatsQueries {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 집계에 쓰는 필드만 읽는다. JSON의 나머지 필드는 무시된다. */
    private static final String COLUMNS = "{'link_id': 'BIGINT', 'clicked_at': 'VARCHAR', "
            + "'visitor_id': 'VARCHAR', 'language': 'VARCHAR', 'device_type': 'VARCHAR', "
            + "'referrer_category': 'VARCHAR', 'is_bot': 'BOOLEAN'}";

    private final String clickLogBase;

    DuckDbQueries(@Value("${app.duckdb.click-log-base:s3://snipy-click-logs}") String clickLogBase) {
        this.clickLogBase = clickLogBase.endsWith("/")
                ? clickLogBase.substring(0, clickLogBase.length() - 1)
                : clickLogBase;
    }

    @Override
    public String dailyStats(LocalDate targetDateKst) {
        return """
                WITH events AS (%s)
                SELECT
                  link_id,
                  CAST(stat_date AS VARCHAR) AS stat_date,
                  COUNT(*) AS click_count,
                  COUNT(DISTINCT visitor_id) AS visitor_count
                FROM events
                WHERE stat_date = DATE '%s'
                GROUP BY link_id, stat_date
                ORDER BY link_id
                """.formatted(
                events(targetDateKst, "visitor_id"),
                targetDateKst.format(YMD)
        );
    }

    @Override
    public String dimensionStats(LocalDate targetDateKst, String dimensionType) {
        String dimensionValue = "COALESCE(%s, 'UNKNOWN') AS dimension_value"
                .formatted(dimensionColumn(dimensionType));

        return """
                WITH events AS (%s)
                SELECT
                  link_id,
                  CAST(stat_date AS VARCHAR) AS stat_date,
                  dimension_value,
                  COUNT(*) AS click_count
                FROM events
                WHERE stat_date = DATE '%s'
                GROUP BY link_id, stat_date, dimension_value
                ORDER BY link_id, dimension_value
                """.formatted(
                events(targetDateKst, dimensionValue),
                targetDateKst.format(YMD)
        );
    }

    /**
     * 대상일(KST)을 포함하는 UTC 파티션 2개에서 봇을 제외한 클릭을 읽고, KST 날짜(stat_date)를 붙인다.
     *
     * clicked_at은 UTC 기준 LocalDateTime 문자열이고 소수점 자릿수가 일정하지 않다 (최대 9자리).
     * DuckDB TIMESTAMP는 마이크로초(6자리)까지라 앞 26자만 잘라서 변환한다.
     */
    private String events(LocalDate targetDateKst, String extraColumn) {
        LocalDate prevUtcPartition = targetDateKst.minusDays(1);

        return """
                  SELECT
                    link_id,
                    %s,
                    CAST(CAST(left(clicked_at, 26) AS TIMESTAMP) + INTERVAL 9 HOUR AS DATE) AS stat_date
                  FROM read_json('%s/year=*/month=*/day=*/*',
                                 format = 'newline_delimited',
                                 hive_partitioning = true,
                                 hive_types_autocast = false,
                                 columns = %s)
                  WHERE (%s OR %s)
                    AND is_bot = false
                """.formatted(
                extraColumn,
                clickLogBase,
                COLUMNS,
                partitionEquals(prevUtcPartition),
                partitionEquals(targetDateKst)
        );
    }

    private static String dimensionColumn(String dimensionType) {
        return switch (dimensionType) {
            case "REFERRER" -> "referrer_category";
            case "DEVICE" -> "device_type";
            // language의 국가 서브태그를 지역 프록시로 쓴다 (예: "ko-KR" -> "KR"). AthenaQueries와 동일
            case "REGION" -> "NULLIF(split_part(language, '-', 2), '')";
            default -> throw new IllegalArgumentException("unknown dimensionType: " + dimensionType);
        };
    }

    private static String partitionEquals(LocalDate date) {
        return "(year='%04d' AND month='%02d' AND day='%02d')"
                .formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}