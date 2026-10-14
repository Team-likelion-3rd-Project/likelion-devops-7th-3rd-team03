package com.example.management.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbQueryExecutorRetryTest {

    private static final String CONNECTION_FAILED =
            "IO Error: Could not establish connection error for HTTP HEAD to 'http://seaweedfs:8333/snipy-click-logs/x.gz'";

    private final DuckDbQueryExecutor executor = DuckDbQueryExecutor.localOnly("/tmp/duckdb-test", 3, 1, 5);

    @Test
    void 일시적_IO_오류는_재시도_후_성공한다() {
        AtomicInteger calls = new AtomicInteger();

        Long result = executor.withRetry(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new SQLException(CONNECTION_FAILED);
            }
            return 42L;
        });

        assertThat(result).isEqualTo(42L);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void 최대_횟수까지_실패하면_예외를_던진다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.withRetry(() -> {
            calls.incrementAndGet();
            throw new SQLException(CONNECTION_FAILED);
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("시도 3/3");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void HTTP_5xx는_재시도한다() {
        AtomicInteger calls = new AtomicInteger();

        executor.withRetry(() -> {
            if (calls.incrementAndGet() < 2) {
                throw new SQLException("HTTP Error: HTTP GET error on 'http://seaweedfs:8333/x.gz' (HTTP 503)");
            }
            return 0L;
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "IO Error: No files found that match the pattern \"s3://snipy-click-logs/year=*/month=*/day=*/*\"",
            "HTTP Error: HTTP GET error on 'http://seaweedfs:8333/x.gz' (HTTP 403)",
            "Out of Memory Error: failed to allocate data of size 32.0 MiB (110.0 MiB/122.0 MiB used)",
            "Parser Error: syntax error at or near \"SELEC\"",
            "Binder Error: Referenced column \"linkid\" not found"
    })
    void 재시도해도_해결되지_않는_오류는_즉시_실패한다(String message) {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.withRetry(() -> {
            calls.incrementAndGet();
            throw new SQLException(message);
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void upsert_등_SQLException이_아닌_예외는_재시도하지_않고_그대로_전파한다() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.withRetry(() -> {
            calls.incrementAndGet();
            throw new BatchCircuitBreakerException("DB 장애");
        })).isInstanceOf(BatchCircuitBreakerException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void 대기_시간은_지수적으로_늘고_최대값을_넘지_않는다() {
        DuckDbQueryExecutor real = DuckDbQueryExecutor.localOnly("/tmp/duckdb-test", 4, 2000, 30000);

        assertThat(real.backoffDelayMs(1)).isBetween(1000L, 2000L);    // 2초 기준
        assertThat(real.backoffDelayMs(2)).isBetween(2000L, 4000L);    // 4초 기준
        assertThat(real.backoffDelayMs(3)).isBetween(4000L, 8000L);    // 8초 기준
        assertThat(real.backoffDelayMs(10)).isBetween(15000L, 30000L); // 최대 30초에서 멈춤
    }
}