package com.example.management.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * K8s CronJob 진입점. "어제(KST) 하루치" 클릭 로그를 Athena로 집계해서
 * link_daily_stats / link_daily_dimension_stats에 UPSERT하고 종료한다.
 *
 * OOM 방지를 위해 대량 쿼리 결과를 전체 List로 적재하지 않고,
 * Athena SDK Paginator 기반 스트리밍(CHUNK_SIZE=1000)으로 처리합니다.
 *
 * 실행: SPRING_PROFILES_ACTIVE=batch, SPRING_MAIN_WEB_APPLICATION_TYPE=none
 *
 * [종료 코드 (Exit Code) 규격]
 *  - 0: 정상 완료 (Success)
 *  - 1: 일반 예외 / 쿼리 실패 / 데이터 처리 실패 (General Error)
 *  - 2: DB 시스템 장애 및 서킷 브레이커 발동 중단 (Circuit Breaker / Fatal System Error)
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class AthenaBatchRunner implements CommandLineRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHUNK_SIZE = 1000;

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_GENERAL_ERROR = 1;
    public static final int EXIT_CIRCUIT_BREAKER = 2;

    private final AthenaQueryExecutor queryExecutor;
    private final DailyStatsUpsertRepository dailyStatsRepository;
    private final DimensionStatsUpsertRepository dimensionStatsRepository;
    private final ApplicationContext applicationContext;

    @Value("${app.athena.workgroup}")
    private String workgroup;

    @Value("${app.athena.database:snipy_click_logs}")
    private String database;

    @Override
    public void run(String... args) {
        LocalDate targetDate = args.length > 0
                ? LocalDate.parse(args[0])
                : LocalDate.now(KST).minusDays(1);

        final int exitCode = runBatch(targetDate);

        // 명시적 exitCode 반환으로 K8s Pod 상태 및 모니터링 알림 연동
        System.exit(SpringApplication.exit(applicationContext, () -> exitCode));
    }

    private int runBatch(LocalDate targetDate) {
        try {
            log.info("Athena 배치 시작. targetDate(KST)={}", targetDate);
            runDailyStats(targetDate);
            runDimensionStats(targetDate);
            log.info("Athena 배치 완료. targetDate(KST)={}", targetDate);
            return EXIT_SUCCESS;
        } catch (BatchCircuitBreakerException e) {
            // [CRITICAL] DB 전면 장애로 인한 서킷 브레이커 발동 -> Exit Code 2 반환
            log.error("[ALERT-CRITICAL] DB 시스템 장애로 서킷 브레이커 발동! 배치 강제 중단. targetDate(KST)={}", targetDate, e);
            return EXIT_CIRCUIT_BREAKER;
        } catch (Exception e) {
            // [ERROR] 일반 쿼리 오류, 파싱 오류 등 -> Exit Code 1 반환
            log.error("[ALERT-ERROR] Athena 배치 처리 실패. targetDate(KST)={}", targetDate, e);
            return EXIT_GENERAL_ERROR;
        }
    }

    private void runDailyStats(LocalDate targetDate) {
        String sql = AthenaQueries.dailyStats(targetDate);
        // 전체 List 대신 청크(1,000건) 단위 스트리밍 소비
        long processedRows = queryExecutor.executeStreaming(
                sql,
                workgroup,
                database,
                CHUNK_SIZE,
                DailyStatRow::from,
                dailyStatsRepository::upsertAll
        );

        log.info("link_daily_stats UPSERT 완료. 총 처리 rows={}", processedRows);
    }

    private void runDimensionStats(LocalDate targetDate) {
        for (String dimensionType : List.of("REFERRER", "DEVICE", "REGION")) {
            String sql = AthenaQueries.dimensionStats(targetDate, dimensionType);

            long processedRows = queryExecutor.executeStreaming(
                    sql,
                    workgroup,
                    database,
                    CHUNK_SIZE,
                    values -> DimensionStatRow.from(values, dimensionType),
                    dimensionStatsRepository::upsertAll
            );

            log.info("link_daily_dimension_stats UPSERT 완료. dimensionType={} 총 처리 rows={}",
                    dimensionType, processedRows);
        }
    }
    /** Athena 결과 한 행 (link_daily_dimension_stats용) */
    public record DailyStatRow(Long linkId, LocalDate statDate, int clickCount, int visitorCount) {
        static DailyStatRow from(List<String> values) {
            return new DailyStatRow(
                    Long.parseLong(values.get(0)),
                    LocalDate.parse(values.get(1)),
                    Integer.parseInt(values.get(2)),
                    Integer.parseInt(values.get(3))
            );
        }
    }
    /** Athena 결과 한 행 (link_daily_dimension_stats용) */
    public record DimensionStatRow(
            Long linkId, LocalDate statDate, String dimensionType, String dimensionValue, int clickCount) {
        static DimensionStatRow from(List<String> values, String dimensionType) {
            return new DimensionStatRow(
                    Long.parseLong(values.get(0)),
                    LocalDate.parse(values.get(1)),
                    dimensionType,
                    values.get(2),
                    Integer.parseInt(values.get(3))
            );
        }
    }
}