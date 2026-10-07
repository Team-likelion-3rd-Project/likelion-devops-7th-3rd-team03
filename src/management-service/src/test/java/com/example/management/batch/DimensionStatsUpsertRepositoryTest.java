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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DimensionStatsUpsertRepository 단위 테스트.
 * - TransactionTemplate은 Pass-through 방식으로 즉시 콜백을 실행하도록 모킹/구현.
 * - chunkSize/maxRetry/bisectFloor/circuitBreakerThreshold를 작은 값으로 주입하고,
 *   Sleeper를 no-op으로 넣어 재시도·bisect를 대기 없이 빠르게 검증.
 * - REGION 차원의 Top-N + ETC 압축 로직 검증 포함.
 */
@ExtendWith(MockitoExtension.class)
class DimensionStatsUpsertRepositoryTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 8, 20);
    private static final DimensionStatsUpsertRepository.Sleeper NO_OP_SLEEPER = millis -> { /* 대기 없음 */ };

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    private DimensionStatsUpsertRepository repository;

    @BeforeEach
    void setUp() {
        repository = newRepository(/*chunkSize*/ 4, /*maxRetry*/ 2, /*bisectFloor*/ 1, /*circuitBreakerThreshold*/ 2);
    }

    private DimensionStatsUpsertRepository newRepository(int chunkSize, int maxRetry, int bisectFloor, int circuitBreakerThreshold) {
        // 단위 테스트용 TransactionTemplate: 별도 트랜잭션 매니저 없이 넘겨받은 콜백을 바로 실행
        TransactionTemplate testTransactionTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };

        return new DimensionStatsUpsertRepository(
                jdbcTemplate,
                testTransactionTemplate,
                chunkSize,
                maxRetry,
                /*baseBackoffMs*/ 1L,
                bisectFloor,
                circuitBreakerThreshold,
                NO_OP_SLEEPER
        );
    }

    @Test
    void REFERRER_모든_row가_성공하면_재시도없이_배치가_끝난다() {
        List<AthenaBatchRunner.DimensionStatRow> rows = createDimensionRows(1L, "REFERRER", 8); // chunkSize=4 -> 청크 2개
        stubAlwaysSucceed();

        repository.upsertAll(rows);

        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void REGION_차원은_상위_10개_초과시_ETC로_압축되어_실행된다() {
        // 한 linkId에 대해 15개의 REGION 데이터 생성
        List<AthenaBatchRunner.DimensionStatRow> rows = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            rows.add(new AthenaBatchRunner.DimensionStatRow(1L, STAT_DATE, "REGION", "City-" + i, i * 10));
        }

        stubAlwaysSucceed();

        repository.upsertAll(rows);

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), captor.capture());

        // 15개 -> 상위 10개 + ETC 1개 = 총 11개로 압축되어 전달되어야 함
        List<SqlParameterSource> allCaptured = captor.getAllValues().stream()
                .flatMap(params -> java.util.Arrays.stream(params))
                .toList();

        assertThat(allCaptured).hasSize(11);

        boolean hasEtc = allCaptured.stream()
                .anyMatch(p -> "ETC".equals(p.getValue("dimensionValue")));
        assertThat(hasEtc).isTrue();
    }

    @Test
    void 일시적_장애는_재시도로_복구된다() {
        List<AthenaBatchRunner.DimensionStatRow> rows = createDimensionRows(1L, "DEVICE", 4); // 단일 청크

        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenThrow(new DataAccessResourceFailureException("일시적 커넥션 오류"))
                .thenReturn(new int[]{1, 1, 1, 1});

        repository.upsertAll(rows);

        verify(jdbcTemplate, times(2)).batchUpdate(anyString(), any(SqlParameterSource[].class));
    }

    @Test
    void 특정_row_불량_시_bisect로_격리되고_정상_row는_완료된다() {
        String poisonValue = "POISON_DEVICE";
        List<AthenaBatchRunner.DimensionStatRow> rows = createDimensionRows(1L, "DEVICE", 7);
        rows.set(2, new AthenaBatchRunner.DimensionStatRow(1L, STAT_DATE, "DEVICE", poisonValue, 1));

        stubFailOnlyForDimensionValue(poisonValue);

        repository.upsertAll(rows); // dead-letter 처리 후 예외 없이 정상 종료되어야 함

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), captor.capture());

        long poisonCalls = captor.getAllValues().stream()
                .filter(params -> params.length == 1 && poisonValue.equals(params[0].getValue("dimensionValue")))
                .count();
        assertThat(poisonCalls).isGreaterThanOrEqualTo(2); // maxRetry=2회 재시도 후 dead-letter 확정
    }

    @Test
    void 연속_시스템_장애_발생_시_서킷브레이커가_배치를_중단시킨다() {
        repository = newRepository(/*chunkSize*/ 2, /*maxRetry*/ 1, /*bisectFloor*/ 1, /*circuitBreakerThreshold*/ 2);
        List<AthenaBatchRunner.DimensionStatRow> rows = createDimensionRows(1L, "REFERRER", 6); // 청크 3개(2, 2, 2)

        stubAlwaysFail();

        assertThatThrownBy(() -> repository.upsertAll(rows))
                .isInstanceOf(BatchCircuitBreakerException.class)
                .hasMessageContaining("연속");

        ArgumentCaptor<SqlParameterSource[]> captor = ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), captor.capture());

        Set<String> attemptedValues = captor.getAllValues().stream()
                .flatMap(params -> java.util.Arrays.stream(params))
                .map(p -> (String) p.getValue("dimensionValue"))
                .collect(Collectors.toSet());

        assertThat(attemptedValues).doesNotContain("Value-5", "Value-6"); // 청크3은 시도조차 안 됨
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
                .thenThrow(new DataAccessResourceFailureException("DB Connection 장애"));
    }

    private void stubFailOnlyForDimensionValue(String poisonValue) {
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                .thenAnswer(invocation -> {
                    SqlParameterSource[] params = invocation.getArgument(1);
                    boolean containsPoison = java.util.Arrays.stream(params)
                            .anyMatch(p -> poisonValue.equals(p.getValue("dimensionValue")));
                    if (containsPoison) {
                        throw new DataIntegrityViolationException("불량 포맷 데이터 예외");
                    }
                    int[] result = new int[params.length];
                    java.util.Arrays.fill(result, 1);
                    return result;
                });
    }

    private List<AthenaBatchRunner.DimensionStatRow> createDimensionRows(Long linkId, String type, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new AthenaBatchRunner.DimensionStatRow(linkId, STAT_DATE, type, "Value-" + i, i * 5))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}