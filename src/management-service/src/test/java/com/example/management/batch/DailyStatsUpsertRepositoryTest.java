package com.example.management.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DailyStatsUpsertRepository 단위 테스트.
 * - TransactionTemplate은 Mock/익명 클래스로 콜백을 즉시 실행(Pass-through)하도록 설정.
 * - chunkSize/maxRetry/bisectFloor/circuitBreakerThreshold를 작은 값으로 주입하고,
 *   Sleeper를 no-op으로 넣어 재시도·bisect를 대기 없이 검증.
 */
@ExtendWith(MockitoExtension.class)
class DailyStatsUpsertRepositoryTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 8, 20);
    private static final DailyStatsUpsertRepository.Sleeper NO_OP_SLEEPER = millis -> { /* 테스트 대기 없음 */ };

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private DailyStatsUpsertRepository repository;

    @BeforeEach
    void setUp() {
        repository = newRepository(/*chunkSize*/ 4, /*maxRetry*/ 2, /*bisectFloor*/ 1, /*circuitBreakerThreshold*/ 2);
    }

    private DailyStatsUpsertRepository newRepository(int chunkSize, int maxRetry, int bisectFloor, int circuitBreakerThreshold) {
        // 단위 테스트용 TransactionTemplate: 별도 트랜잭션 매니저 없이 넘겨받은 콜백을 바로 실행
        TransactionTemplate testTransactionTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };

        return new DailyStatsUpsertRepository(
                jdbcTemplate,
                testTransactionTemplate,
                chunkSize,
                maxRetry,
                /*baseBackoffMillis*/ 1L,
                bisectFloor,
                circuitBreakerThreshold,
                NO_OP_SLEEPER
        );
    }

    @Test
    void 모든_row가_한번에_성공하면_재시도없이_끝난다() {
        List<AthenaBatchRunner.DailyStatRow> rows = rows(1, 8); // chunkSize=4 → 청크 2개
        stubAlwaysSucceed();

        repository.upsertAll(rows);

        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void 일시적_실패는_재시도로_복구된다() {
        List<AthenaBatchRunner.DailyStatRow> rows = rows(1, 4); // 단일 청크

        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(new DataAccessResourceFailureException("일시적 커넥션 오류"))
                .thenReturn(new int[]{1, 1, 1, 1});

        repository.upsertAll(rows);

        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void 특정_row가_계속_실패하면_bisect로_격리되고_배치는_계속된다() {
        long poisonLinkId = 999L;
        List<AthenaBatchRunner.DailyStatRow> rows = rows(1, 7);
        rows.set(3, poisonRow(poisonLinkId)); // 첫 청크(1~4) 안에 섞어 넣음

        // poison row는 데이터 무결성 오류(DataIntegrityViolationException)로 시뮬레이션
        stubFailOnlyForLinkId(poisonLinkId);

        repository.upsertAll(rows); // dead-letter로 격리 후 정상 완료되어야 함

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), captor.capture());

        long poisonOnlyCalls = captor.getAllValues().stream()
                .filter(params -> params.length == 1 && linkIdOf(params[0]) == poisonLinkId)
                .count();
        assertThat(poisonOnlyCalls).isGreaterThanOrEqualTo(2); // maxRetry=2회 시도 후 dead-letter 확정

        boolean anyCleanBatchSucceeded = captor.getAllValues().stream()
                .anyMatch(params -> params.length > 1
                        && java.util.Arrays.stream(params).noneMatch(p -> linkIdOf(p) == poisonLinkId));
        assertThat(anyCleanBatchSucceeded).isTrue();
    }

    @Test
    void 연속으로_시스템장애가_발생하면_서킷브레이커가_배치를_중단시킨다() {
        repository = newRepository(/*chunkSize*/ 2, /*maxRetry*/ 1, /*bisectFloor*/ 1, /*circuitBreakerThreshold*/ 2);
        List<AthenaBatchRunner.DailyStatRow> rows = rows(1, 6); // 청크 3개(2, 2, 2)

        stubAlwaysFail();

        assertThatThrownBy(() -> repository.upsertAll(rows))
                .isInstanceOf(BatchCircuitBreakerException.class)
                .hasMessageContaining("연속");

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), captor.capture());

        Set<Long> attemptedLinkIds = captor.getAllValues().stream()
                .flatMap(params -> java.util.Arrays.stream(params))
                .map(this::linkIdOf)
                .collect(Collectors.toSet());

        assertThat(attemptedLinkIds).doesNotContain(5L, 6L); // 청크3(id: 5,6)은 시도조차 안 됨
    }

    // ---- 헬퍼 ----

    private void stubAlwaysSucceed() {
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenAnswer(invocation -> {
                    SqlParameterSource[] params = invocation.getArgument(1);
                    int[] result = new int[params.length];
                    java.util.Arrays.fill(result, 1);
                    return result;
                });
    }

    private void stubAlwaysFail() {
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(new DataAccessResourceFailureException("DB Connection 장애 시뮬레이션"));
    }

    private void stubFailOnlyForLinkId(long poisonLinkId) {
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenAnswer(invocation -> {
                    SqlParameterSource[] params = invocation.getArgument(1);
                    boolean containsPoison = java.util.Arrays.stream(params)
                            .anyMatch(p -> linkIdOf(p) == poisonLinkId);
                    if (containsPoison) {
                        throw new DataIntegrityViolationException("poison row 데이터 무결성 제약 위반");
                    }
                    int[] result = new int[params.length];
                    java.util.Arrays.fill(result, 1);
                    return result;
                });
    }

    private long linkIdOf(SqlParameterSource p) {
        return (Long) p.getValue("linkId");
    }

    private List<AthenaBatchRunner.DailyStatRow> rows(int fromInclusive, int toInclusive) {
        return LongStream.rangeClosed(fromInclusive, toInclusive)
                .mapToObj(linkId -> new AthenaBatchRunner.DailyStatRow(linkId, STAT_DATE, 10, 5))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    private AthenaBatchRunner.DailyStatRow poisonRow(long linkId) {
        return new AthenaBatchRunner.DailyStatRow(linkId, STAT_DATE, 1, 1);
    }
}