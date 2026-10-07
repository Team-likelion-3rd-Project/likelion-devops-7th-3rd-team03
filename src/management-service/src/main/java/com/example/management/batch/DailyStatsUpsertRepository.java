package com.example.management.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * link_daily_stats 대량 UPSERT 저장소.
 *
 * - @Profile("batch"): 웹 서버 기동 시 불필요한 빈 로딩을 막고 배치 전용 컨텍스트에서만 동작.
 * - TransactionTemplate: upsertAll() 전체 트랜잭션 대신 청크/bisect 단위 독립 트랜잭션 보장 (Self-Invocation 문제 해결).
 * - bisectFloor=1: 불량 데이터 발견 시 1건 단위까지 끝까지 쪼개어 정상 데이터 유실을 0으로 방지.
 * - 서킷 브레이커: dead-letter(부분 실패)가 아닌 DB 시스템 연속 장애(완전 실패) 시에만 카운트 증가.
 */
@Slf4j
@Repository
@Profile("batch")
public class DailyStatsUpsertRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO link_daily_stats (link_id, stat_date, click_count, visitor_count)
            VALUES (:linkId, :statDate, :clickCount, :visitorCount)
            ON DUPLICATE KEY UPDATE
                click_count = VALUES(click_count),
                visitor_count = VALUES(visitor_count)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private final int chunkSize;
    private final int maxRetry;
    private final long baseBackoffMillis;
    private final int bisectFloor;
    private final int circuitBreakerThreshold;
    private final Sleeper sleeper;

    @Autowired
    public DailyStatsUpsertRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                      PlatformTransactionManager transactionManager) {
        // bisectFloor 기본값을 5 -> 1로 설정하여 정상 row가 함께 dead-letter로 버려지는 문제 해결
        this(jdbcTemplate, new TransactionTemplate(transactionManager), 1000, 3, 500L, 1, 3, DEFAULT_SLEEPER);
    }

    /** 테스트 전용 생성자 */
    DailyStatsUpsertRepository(NamedParameterJdbcTemplate jdbcTemplate,
                               TransactionTemplate transactionTemplate,
                               int chunkSize,
                               int maxRetry,
                               long baseBackoffMillis,
                               int bisectFloor,
                               int circuitBreakerThreshold,
                               Sleeper sleeper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.chunkSize = chunkSize;
        this.maxRetry = maxRetry;
        this.baseBackoffMillis = baseBackoffMillis;
        this.bisectFloor = bisectFloor;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.sleeper = sleeper;
    }

    private static final Sleeper DEFAULT_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트됨", ie);
        }
    };

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    public void upsertAll(List<AthenaBatchRunner.DailyStatRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        int totalChunks = (rows.size() + chunkSize - 1) / chunkSize;
        int chunkIndex = 0;
        int failedRowCount = 0;
        int consecutiveSystemFailures = 0; // 완전 실패(시스템 장애) 연속 횟수

        for (int i = 0; i < rows.size(); i += chunkSize) {
            chunkIndex++;
            List<AthenaBatchRunner.DailyStatRow> chunk = rows.subList(i, Math.min(i + chunkSize, rows.size()));

            log.info("[daily-stats-upsert] chunk {}/{} 시작. size={}", chunkIndex, totalChunks, chunk.size());
            long startedAt = System.currentTimeMillis();

            try {
                List<AthenaBatchRunner.DailyStatRow> deadLetters = upsertWithRetryAndBisect(chunk, chunkIndex, 1);
                long elapsedMs = System.currentTimeMillis() - startedAt;
                // 부분 실패(일부 dead-letter 격리)라도 청크 처리가 완료되었으면 시스템 장애 카운트는 리셋
                consecutiveSystemFailures = 0;

                if (deadLetters.isEmpty()) {
                    log.info("[daily-stats-upsert] chunk {}/{} 완료. size={}, elapsedMs={}, sample={}",
                            chunkIndex, totalChunks, chunk.size(), elapsedMs, sampleRange(chunk));
                } else {
                    failedRowCount += deadLetters.size();
                    log.error("[daily-stats-upsert] chunk {}/{} 일부 데이터 결함 격리. size={}, deadLetters={}, rows={}",
                            chunkIndex, totalChunks, chunk.size(), deadLetters.size(), summarize(deadLetters));
                }
            } catch (SystemLevelBatchException e) {
                // DB 다운/커넥션 고갈 등 완전 실패 시에만 서킷 브레이커 카운트 증가
                consecutiveSystemFailures++;
                log.error("[daily-stats-upsert] chunk {}/{} DB 시스템 장애 발생 (연속 {}회): {}",
                        chunkIndex, totalChunks, consecutiveSystemFailures, e.getMessage());

                if (consecutiveSystemFailures >= circuitBreakerThreshold) {
                    log.error("[daily-stats-upsert] 서킷 브레이커 발동: 시스템 연속 장애 {}회 도달로 배치 즉시 중단. chunk={}/{}",
                            consecutiveSystemFailures, chunkIndex, totalChunks);
                    throw new BatchCircuitBreakerException(
                            "시스템 연속 장애 " + consecutiveSystemFailures + "회 발생으로 배치 중단 (chunk "
                                    + chunkIndex + "/" + totalChunks + ")", e);
                }
            }
        }

        log.info("link_daily_stats 전체 UPSERT 완료. totalRows={}, chunkSize={}, failedRows={}",
                rows.size(), chunkSize, failedRowCount);
    }

    private List<AthenaBatchRunner.DailyStatRow> upsertWithRetryAndBisect(
            List<AthenaBatchRunner.DailyStatRow> chunk, int chunkIndex, int depth) {

        DataAccessException lastException = null;

        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                // 청크/서브청크 단위 독립 트랜잭션 실행
                transactionTemplate.executeWithoutResult(status -> executeBatchUpsert(chunk));

                if (attempt > 1) {
                    log.warn("[daily-stats-upsert] chunk {} (depth={}) {}번째 시도에 복구 성공. size={}",
                            chunkIndex, depth, attempt, chunk.size());
                }
                return List.of();
            } catch (DataAccessException e) {
                lastException = e;
                log.warn("[daily-stats-upsert] chunk {} (depth={}) {}번째 시도 실패. size={}, cause={}",
                        chunkIndex, depth, attempt, chunk.size(), e.getMessage());

                if (attempt < maxRetry) {
                    sleepBackoff(attempt);
                }
            }
        }

        // 시스템 레벨 장애는 bisect 하지 않고 즉시 상위로 던져 서킷 브레이커 카운트 누적
        if (isSystemLevelException(lastException)) {
            throw new SystemLevelBatchException("DB 시스템 장애(커넥션/다운/락타임아웃)로 인한 처리 불가", lastException);
        }

        if (chunk.size() <= bisectFloor) {
            log.error("[daily-stats-upsert] chunk {} (depth={}) bisectFloor({}) 도달 → dead-letter 확정. size={}",
                    chunkIndex, depth, bisectFloor, chunk.size());
            return chunk;
        }

        log.warn("[daily-stats-upsert] chunk {} (depth={}) 데이터 결함 격리를 위한 bisect 진행. size={}",
                chunkIndex, depth, chunk.size());

        int mid = chunk.size() / 2;
        List<AthenaBatchRunner.DailyStatRow> left = chunk.subList(0, mid);
        List<AthenaBatchRunner.DailyStatRow> right = chunk.subList(mid, chunk.size());

        List<AthenaBatchRunner.DailyStatRow> deadLetters = new ArrayList<>();
        deadLetters.addAll(upsertWithRetryAndBisect(left, chunkIndex, depth + 1));
        deadLetters.addAll(upsertWithRetryAndBisect(right, chunkIndex, depth + 1));
        return deadLetters;
    }

    private void executeBatchUpsert(List<AthenaBatchRunner.DailyStatRow> chunk) {
        SqlParameterSource[] params = new SqlParameterSource[chunk.size()];
        for (int i = 0; i < chunk.size(); i++) {
            AthenaBatchRunner.DailyStatRow row = chunk.get(i);
            params[i] = new MapSqlParameterSource()
                    .addValue("linkId", row.linkId())
                    .addValue("statDate", row.statDate())
                    .addValue("clickCount", row.clickCount())
                    .addValue("visitorCount", row.visitorCount());
        }

        jdbcTemplate.batchUpdate(UPSERT_SQL, params);
    }

    /**
     * Spring DataAccessException 계층 구조 완벽 대응:
     * - DataAccessResourceFailureException (NonTransientDataAccessException 하위, 커넥션 풀 고갈/연결 실패)
     * - CannotGetJdbcConnectionException (JDBC 연결 불가)
     * - TransientDataAccessException (일시적 락, 네트워크 순단)
     * - CannotAcquireLockException, PessimisticLockingFailureException, QueryTimeoutException
     */
    private boolean isSystemLevelException(DataAccessException e) {
        if (e == null) return false;
        return e instanceof DataAccessResourceFailureException
                || e instanceof CannotGetJdbcConnectionException
                || e instanceof TransientDataAccessException
                || e instanceof CannotAcquireLockException
                || e instanceof PessimisticLockingFailureException
                || e instanceof QueryTimeoutException;
    }

    private void sleepBackoff(int attempt) {
        long backoffMs = baseBackoffMillis * (1L << (attempt - 1));
        sleeper.sleep(backoffMs);
    }

    private String sampleRange(List<AthenaBatchRunner.DailyStatRow> chunk) {
        AthenaBatchRunner.DailyStatRow first = chunk.get(0);
        AthenaBatchRunner.DailyStatRow last = chunk.get(chunk.size() - 1);
        return "first=" + first.linkId() + "|" + first.statDate()
                + ", last=" + last.linkId() + "|" + last.statDate();
    }

    private String summarize(List<AthenaBatchRunner.DailyStatRow> rows) {
        return rows.stream()
                .limit(20)
                .map(r -> r.linkId() + "|" + r.statDate())
                .collect(Collectors.joining(", "));
    }

    public static class SystemLevelBatchException extends RuntimeException {
        public SystemLevelBatchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}