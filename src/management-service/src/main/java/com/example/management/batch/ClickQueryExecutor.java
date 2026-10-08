package com.example.management.batch;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 클릭 로그 집계 쿼리 실행기. 결과를 chunkSize 단위로 스트리밍해 consumer에 넘긴다.
 * 구현체: AthenaQueryExecutor(app.batch.engine=athena, 기본값) / DuckDB 구현체(예정)
 */
public interface ClickQueryExecutor {

    <T> long executeStreaming(String sql,
                              int chunkSize,
                              Function<List<String>, T> rowMapper,
                              Consumer<List<T>> chunkConsumer);
}