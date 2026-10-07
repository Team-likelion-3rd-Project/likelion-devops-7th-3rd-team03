package com.example.management.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * link_daily_dimension_stats UPSERT 저장소.
 *
 * - @Profile("batch"): 배치 프로필에서만 빈 등록.
 * - bisectFloor=1: 문제 row 단건만 격리하여 정상 row 유실 방지.
 * - TransactionTemplate: 청크 단위 독립 트랜잭션 관리.
 */
@Slf4j
@Repository
@Profile("batch")
public class DimensionStatsUpsertRepository {

    private static final int REGION_TOP_N = 10;
    private static final String ETC = "ETC";

    private static final String UPSERT_SQL = """
            INSERT INTO link_daily_dimension_stats
                (link_id, stat_date, dimension_type, dimension_value, click_count)
            VALUES (:linkId, :statDate, :dimensionType, :dimensionValue, :clickCount)
            ON DUPLICATE KEY UPDATE
                click_count = VALUES(click_count)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private final int chunkSize;
    private final int maxRetry;
    private final long baseBackoffMs;
    private final int bisectFloor;
    private final int circuitBreakerThreshold;
    private final Sleeper sleeper;

    @Autowired
    public DimensionStatsUpsertRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                          PlatformTransactionManager transactionManager) {
        // bisectFloor를 1로 변경
        this(jdbcTemplate, new TransactionTemplate(transactionManager), 1000, 3, 200L, 1, 3, DEFAULT_SLEEPER);
    }

    /** 테스트 전용 생성자 */
    DimensionStatsUpsertRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                   TransactionTemplate transactionTemplate,
                                   int chunkSize,
                                   int maxRetry,
                                   long baseBackoffMs,
                                   int bisectFloor,
                                   int circuitBreakerThreshold,
                                   Sleeper sleeper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.chunkSize = chunkSize;
        this.maxRetry = maxRetry;
        this.baseBackoffMs = baseBackoffMs;
        this.bisectFloor = bisectFloor;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.sleeper = sleeper;
    }

    private static final Sleeper DEFAULT_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", ie);
        }
    };

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    public void upsertAll(List<AthenaBatchRunner.DimensionStatRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<AthenaBatchRunner.DimensionStatRow> effectiveRows =
                "REGION".equals(rows.get(0).dimensionType())
                        ? bucketTopNPerLink(rows)
                        : rows;

        int totalChunks = (effectiveRows.size() + chunkSize - 1) / chunkSize;
        int chunkIndex = 0;
        int failedRowCount = 0;
        int consecutiveSystemFailures = 0;

        for (int i = 0; i < effectiveRows.size(); i += chunkSize) {
            chunkIndex++;
            List<AthenaBatchRunner.DimensionStatRow> chunk =
                    effectiveRows.subList(i, Math.min(i + chunkSize, effectiveRows.size()));

            log.info("[dimension-upsert] chunk {}/{} 시작. size={}", chunkIndex, totalChunks, chunk.size());
            long startedAt = System.currentTimeMillis();

            try {
                List<AthenaBatchRunner.DimensionStatRow> deadLetters = upsertWithRetryAndBisect(chunk, chunkIndex, 1);
                long elapsedMs = System.currentTimeMillis() - startedAt;

                consecutiveSystemFailures = 0;

                if (deadLetters.isEmpty()) {
                    log.info("[dimension-upsert] chunk {}/{} 완료. size={}, elapsedMs={}, sample={}",
                            chunkIndex, totalChunks, chunk.size(), elapsedMs, sampleRange(chunk));
                } else {
                    failedRowCount += deadLetters.size();
                    log.error("[dimension-upsert] chunk {}/{} 일부 데이터 결함 격리. size={}, deadLetters={}, rows={}",
                            chunkIndex, totalChunks, chunk.size(), deadLetters.size(), summarize(deadLetters));
                }
            } catch (SystemLevelBatchException e) {
                consecutiveSystemFailures++;
                log.error("[dimension-upsert] chunk {}/{} 시스템 완전 실패 발생 (연속 {}회): {}",
                        chunkIndex, totalChunks, consecutiveSystemFailures, e.getMessage());

                if (consecutiveSystemFailures >= circuitBreakerThreshold) {
                    log.error("[dimension-upsert] 서킷 브레이커 발동: 시스템 연속 장애 {}회 도달로 배치 중단. dimensionType={}, chunk={}/{}",
                            consecutiveSystemFailures, rows.get(0).dimensionType(), chunkIndex, totalChunks);
                    throw new BatchCircuitBreakerException(
                            "시스템 연속 장애 " + consecutiveSystemFailures + "회 발생으로 배치 중단 (chunk "
                                    + chunkIndex + "/" + totalChunks + ")", e);
                }
            }
        }

        log.info("link_daily_dimension_stats 전체 UPSERT 완료. dimensionType={}, rawRows={}, effectiveRows={}, chunkSize={}, failedRows={}",
                rows.get(0).dimensionType(), rows.size(), effectiveRows.size(), chunkSize, failedRowCount);
    }

    private List<AthenaBatchRunner.DimensionStatRow> upsertWithRetryAndBisect(
            List<AthenaBatchRunner.DimensionStatRow> chunk, int chunkIndex, int depth) {

        DataAccessException lastException = null;

        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> executeBatchUpsert(chunk));

                if (attempt > 1) {
                    log.warn("[dimension-upsert] chunk {} (depth={}) {}번째 시도에 복구 성공. size={}",
                            chunkIndex, depth, attempt, chunk.size());
                }
                return List.of();
            } catch (DataAccessException e) {
                lastException = e;
                log.warn("[dimension-upsert] chunk {} (depth={}) {}번째 시도 실패. size={}, cause={}",
                        chunkIndex, depth, attempt, chunk.size(), e.getMessage());

                if (attempt < maxRetry) {
                    sleepBackoff(attempt);
                }
            }
        }

        if (chunk.size() <= bisectFloor) {
            if (isSystemLevelException(lastException)) {
                throw new SystemLevelBatchException("DB 시스템 장애로 인한 처리 불가", lastException);
            }

            log.error("[dimension-upsert] chunk {} (depth={}) bisectFloor({}) 도달 → 불량 데이터 단건 dead-letter 격리. row={}",
                    chunkIndex, depth, bisectFloor, summarize(chunk));
            return chunk;
        }

        log.warn("[dimension-upsert] chunk {} (depth={}) 재시도 소진 → bisect 분할 진행. size={}",
                chunkIndex, depth, chunk.size());

        int mid = chunk.size() / 2;
        List<AthenaBatchRunner.DimensionStatRow> left = chunk.subList(0, mid);
        List<AthenaBatchRunner.DimensionStatRow> right = chunk.subList(mid, chunk.size());

        List<AthenaBatchRunner.DimensionStatRow> deadLetters = new ArrayList<>();
        deadLetters.addAll(upsertWithRetryAndBisect(left, chunkIndex, depth + 1));
        deadLetters.addAll(upsertWithRetryAndBisect(right, chunkIndex, depth + 1));
        return deadLetters;
    }

    private void executeBatchUpsert(List<AthenaBatchRunner.DimensionStatRow> chunk) {
        SqlParameterSource[] params = new SqlParameterSource[chunk.size()];
        for (int i = 0; i < chunk.size(); i++) {
            AthenaBatchRunner.DimensionStatRow row = chunk.get(i);
            params[i] = new MapSqlParameterSource()
                    .addValue("linkId", row.linkId())
                    .addValue("statDate", row.statDate())
                    .addValue("dimensionType", row.dimensionType())
                    .addValue("dimensionValue", row.dimensionValue())
                    .addValue("clickCount", row.clickCount());
        }

        int[] updateCounts = jdbcTemplate.batchUpdate(UPSERT_SQL, params);

        if (log.isDebugEnabled()) {
            int inserted = 0, updated = 0, unchanged = 0;
            for (int c : updateCounts) {
                if (c == 1) inserted++;
                else if (c == 2) updated++;
                else unchanged++;
            }
            log.debug("[dimension-upsert] batchUpdate 완료: inserted={}, updated={}, unchanged={}",
                    inserted, updated, unchanged);
        }
    }

    private boolean isSystemLevelException(DataAccessException e) {
        return e instanceof TransientDataAccessException
                || (e.getMessage() != null && e.getMessage().contains("Connection"));
    }

    private void sleepBackoff(int attempt) {
        long backoffMs = baseBackoffMs * (1L << (attempt - 1));
        sleeper.sleep(backoffMs);
    }

    private String sampleRange(List<AthenaBatchRunner.DimensionStatRow> chunk) {
        AthenaBatchRunner.DimensionStatRow first = chunk.get(0);
        AthenaBatchRunner.DimensionStatRow last = chunk.get(chunk.size() - 1);
        return "first=" + first.linkId() + "|" + first.statDate() + "|" + first.dimensionValue()
                + ", last=" + last.linkId() + "|" + last.statDate() + "|" + last.dimensionValue();
    }

    private String summarize(List<AthenaBatchRunner.DimensionStatRow> rows) {
        return rows.stream()
                .limit(20)
                .map(r -> r.linkId() + "|" + r.statDate() + "|" + r.dimensionValue())
                .collect(Collectors.joining(", "));
    }

    private List<AthenaBatchRunner.DimensionStatRow> bucketTopNPerLink(
            List<AthenaBatchRunner.DimensionStatRow> rows) {

        Map<String, List<AthenaBatchRunner.DimensionStatRow>> grouped = rows.stream()
                .collect(Collectors.groupingBy(r -> r.linkId() + "|" + r.statDate()));

        List<AthenaBatchRunner.DimensionStatRow> result = new ArrayList<>();
        for (List<AthenaBatchRunner.DimensionStatRow> group : grouped.values()) {
            List<AthenaBatchRunner.DimensionStatRow> sorted = group.stream()
                    .sorted(Comparator.comparingInt(AthenaBatchRunner.DimensionStatRow::clickCount).reversed())
                    .toList();

            result.addAll(sorted.subList(0, Math.min(REGION_TOP_N, sorted.size())));

            if (sorted.size() > REGION_TOP_N) {
                List<AthenaBatchRunner.DimensionStatRow> rest = sorted.subList(REGION_TOP_N, sorted.size());
                int etcTotal = rest.stream().mapToInt(AthenaBatchRunner.DimensionStatRow::clickCount).sum();
                AthenaBatchRunner.DimensionStatRow first = rest.get(0);
                result.add(new AthenaBatchRunner.DimensionStatRow(
                        first.linkId(), first.statDate(), first.dimensionType(), ETC, etcTotal));
            }
        }
        return result;
    }

    public static class SystemLevelBatchException extends RuntimeException {
        public SystemLevelBatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}