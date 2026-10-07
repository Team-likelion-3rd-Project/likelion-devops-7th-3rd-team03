package com.example.management.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;
import software.amazon.awssdk.services.athena.paginators.GetQueryResultsIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class AthenaQueryExecutor {

    private static final int MAX_POLL_ATTEMPTS = 150; // 2초 간격 x 150회 = 최대 5분 폴링 대기
    private static final long POLL_INTERVAL_MS = 2000L;

    private final AthenaClient athenaClient;

    /**
     * 쿼리를 비동기로 실행하고 완료될 때까지 대기한 뒤,
     * 결과를 chunkSize 단위로 스트리밍하여 consumer에게 넘겨 처리합니다.
     *
     * @param sql 실행할 SQL
     * @param workgroup Athena 워크그룹
     * @param database 대상 DB명
     * @param chunkSize 한 번에 처리할 row 단위 (예: 1000)
     * @param rowMapper String 리스트 -> 엔티티/DTO 변환 함수
     * @param chunkConsumer 청크 단위 데이터를 소비할 콜백 (예: repository::upsertAll)
     * @return 전체 처리된 row 수 (헤더 제외)
     */
    public <T> long executeStreaming(
            String sql,
            String workgroup,
            String database,
            int chunkSize,
            Function<List<String>, T> rowMapper,
            Consumer<List<T>> chunkConsumer) {

        // 1. 쿼리 실행 시작
        String queryExecutionId = startQueryExecution(sql, workgroup, database);
        log.info("[athena-executor] 쿼리 실행 시작. executionId={}", queryExecutionId);

        // 2. 쿼리 완료 대기 (POLLING)
        waitForQueryCompletion(queryExecutionId);
        log.info("[athena-executor] 쿼리 완료. 결과 스트리밍 시작. executionId={}", queryExecutionId);

        // 3. 페이지네이션을 통한 청크 스트리밍 처리
        return streamResults(queryExecutionId, chunkSize, rowMapper, chunkConsumer);
    }

    private String startQueryExecution(String sql, String workgroup, String database) {
        StartQueryExecutionRequest request = StartQueryExecutionRequest.builder()
                .queryString(sql)
                .workGroup(workgroup)
                .queryExecutionContext(QueryExecutionContext.builder().database(database).build())
                .build();

        StartQueryExecutionResponse response = athenaClient.startQueryExecution(request);
        return response.queryExecutionId();
    }

    private void waitForQueryCompletion(String queryExecutionId) {
        GetQueryExecutionRequest request = GetQueryExecutionRequest.builder()
                .queryExecutionId(queryExecutionId)
                .build();

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            GetQueryExecutionResponse response = athenaClient.getQueryExecution(request);
            QueryExecutionStatus status = response.queryExecution().status();
            QueryExecutionState state = status.state();

            if (state == QueryExecutionState.SUCCEEDED) {
                return;
            } else if (state == QueryExecutionState.FAILED || state == QueryExecutionState.CANCELLED) {
                String reason = status.stateChangeReason();
                throw new IllegalStateException("Athena 쿼리 실패: " + state + ", 원인: " + reason);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Athena 쿼리 대기 중 인터럽트 발생", e);
            }
        }

        // 최대 폴링 횟수 초과 시 무한 대기 방지
        throw new IllegalStateException(
                "Athena 쿼리 대기 시간 초과 (최대 " + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "초 초과). executionId=" + queryExecutionId);
    }

    private <T> long streamResults(
            String queryExecutionId,
            int chunkSize,
            Function<List<String>, T> rowMapper,
            Consumer<List<T>> chunkConsumer) {

        GetQueryResultsRequest request = GetQueryResultsRequest.builder()
                .queryExecutionId(queryExecutionId)
                .maxResults(Math.min(chunkSize, 1000)) // Athena API 최대 1,000건
                .build();

        GetQueryResultsIterable paginator = athenaClient.getQueryResultsPaginator(request);

        List<T> buffer = new ArrayList<>(chunkSize);
        long totalProcessedRows = 0;
        boolean isFirstRow = true; // 첫 번째 row는 CSV/Athena 컬럼 헤더

        for (GetQueryResultsResponse page : paginator) {
            for (Row row : page.resultSet().rows()) {
                if (isFirstRow) {
                    isFirstRow = false;
                    continue; // 헤더 스킵
                }
                // Athena Row -> List<String> 변환
                List<String> values = row.data().stream()
                        .map(Datum::varCharValue)
                        .toList();

                buffer.add(rowMapper.apply(values));
                totalProcessedRows++;
                // 버퍼가 청크 크기에 도달하면 즉시 처리 후 비움 -> Heap 메모리 안정화
                if (buffer.size() >= chunkSize) {
                    chunkConsumer.accept(new ArrayList<>(buffer));
                    buffer.clear(); //GC 대상 전환
                }
            }
        }
        // 남아있는 마지막 자투리 청크 처리
        if (!buffer.isEmpty()) {
            chunkConsumer.accept(new ArrayList<>(buffer));
            buffer.clear();
        }

        return totalProcessedRows;
    }
}