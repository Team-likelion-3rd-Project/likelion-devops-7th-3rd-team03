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
 *  - 클릭 수는 COUNT(DISTINCT event_id). 클릭은 "최소 한 번" 전달돼 같은 이벤트가 두 번 저장될 수 있다 (#314)
 */
@Component
@Profile("batch")
@ConditionalOnProperty(name = "app.batch.engine", havingValue = "duckdb")
final class DuckDbQueries implements ClickStatsQueries {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 집계에 쓰는 필드만 읽는다. JSON의 나머지 필드는 무시되고, 스키마 1 이벤트에 없는 country·region 은 NULL 이다. */
    private static final String COLUMNS = "{'event_id': 'VARCHAR', 'link_id': 'BIGINT', 'clicked_at': 'VARCHAR', "
            + "'visitor_id': 'VARCHAR', 'language': 'VARCHAR', 'device_type': 'VARCHAR', "
            + "'operating_system': 'VARCHAR', 'browser': 'VARCHAR', "
            + "'country': 'VARCHAR', 'region': 'VARCHAR', "
            + "'referrer_category': 'VARCHAR', 'is_bot': 'BOOLEAN'}";

    private final String clickLogBase;
    private final boolean exactVisitorCount;

    DuckDbQueries(
            @Value("${app.duckdb.click-log-base:s3://snipy-click-logs}") String clickLogBase,
            @Value("${app.duckdb.exact-visitor-count:true}") boolean exactVisitorCount) {
        this.clickLogBase = clickLogBase.endsWith("/")
                ? clickLogBase.substring(0, clickLogBase.length() - 1)
                : clickLogBase;
        this.exactVisitorCount = exactVisitorCount;
    }

    @Override
    public String dailyStats(LocalDate targetDateKst) {
        // 정확한 값(COUNT DISTINCT)은 방문자 ID를 전부 메모리에 들고 있어야 해서 대량 데이터에서 OOM 위험이 크다.
        // 기본은 Athena(APPROX_DISTINCT)와 같은 근사치(HyperLogLog)로, 메모리를 거의 일정하게 쓴다
        String visitorCount = exactVisitorCount
                ? "COUNT(DISTINCT visitor_id)"
                : "approx_count_distinct(visitor_id)";

        return """
                WITH events AS (%s)
                SELECT
                  link_id,
                  CAST(stat_date AS VARCHAR) AS stat_date,
                  COUNT(DISTINCT event_id) AS click_count,
                  %s AS visitor_count
                FROM events
                WHERE stat_date = DATE '%s'
                GROUP BY link_id, stat_date
                ORDER BY link_id
                """.formatted(
                events(targetDateKst, "visitor_id"),
                visitorCount,
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
                  COUNT(DISTINCT event_id) AS click_count,
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
                    event_id,
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
            case "OS" -> "operating_system";
            case "BROWSER" -> "browser";
            // KST 시각(00~23). clicked_at 은 UTC 라 +9 시간
            case "HOUR" -> "lpad(CAST(hour(CAST(left(clicked_at, 26) AS TIMESTAMP) + INTERVAL 9 HOUR) AS VARCHAR), 2, '0')";
            // CloudFront 위치 헤더(#207) → 없으면(온프렘, 스키마 1) 언어 태그의 국가 서브태그 (ko-KR -> KR)
            case "COUNTRY" -> "COALESCE(NULLIF(country, ''), NULLIF(upper(split_part(language, '-', 2)), ''))";
            // CloudFront 시·도 코드(#207)는 국가 안에서만 의미가 있어(예: 11 = 한국 서울) 국가를 붙인다 → KR-11.
            // 온프렘에는 없어 UNKNOWN 이 된다
            case "REGION" -> "CASE WHEN NULLIF(region, '') IS NULL THEN NULL ELSE country || '-' || region END";
            default -> throw new IllegalArgumentException("unknown dimensionType: " + dimensionType);
        };
    }

    private static String partitionEquals(LocalDate date) {
        return "(year='%04d' AND month='%02d' AND day='%02d')"
                .formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}