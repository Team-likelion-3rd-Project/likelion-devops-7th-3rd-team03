package com.example.management.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 온프렘 환경 전용 쿼리 실행기. app.batch.engine=duckdb일 때만 등록된다.
 *
 * DuckDB를 배치 JVM 안에서 인메모리로 띄워, SeaweedFS(S3 호환)에 쌓인 클릭 로그를 SQL로 집계한다.
 * Athena와 달리 별도 서버가 없고 쿼리도 동기로 실행된다.
 *
 * OOM 방지:
 *  - jdbc_stream_results로 결과를 한 번에 메모리에 올리지 않고 스트리밍한다.
 *  - memory_limit으로 DuckDB가 쓰는 메모리 상한을 둔다. DuckDB 메모리는 JVM 힙 밖(네이티브)에서
 *    잡히므로, 컨테이너 limit 안에서 JVM 힙과 나눠 써야 한다.
 *  - threads, preserve_insertion_order로 스레드별 버퍼 사용량을 줄인다.
 */
@Slf4j
@Component
@Profile("batch")
@ConditionalOnProperty(name = "app.batch.engine", havingValue = "duckdb")
public class DuckDbQueryExecutor implements ClickQueryExecutor {

    private final String s3Endpoint;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final String s3Region;
    private final boolean s3UseSsl;
    private final String memoryLimit;
    private final String tempDirectory;
    private final String extensionDirectory;
    private final int threads;
    private final boolean preserveInsertionOrder;

    public DuckDbQueryExecutor(
            @Value("${app.duckdb.s3-endpoint:}") String s3Endpoint,
            @Value("${app.duckdb.s3-access-key:}") String s3AccessKey,
            @Value("${app.duckdb.s3-secret-key:}") String s3SecretKey,
            @Value("${app.duckdb.s3-region:us-east-1}") String s3Region,
            @Value("${app.duckdb.s3-use-ssl:false}") boolean s3UseSsl,
            @Value("${app.duckdb.memory-limit:256MB}") String memoryLimit,
            @Value("${app.duckdb.temp-directory:/tmp/duckdb}") String tempDirectory,
            @Value("${app.duckdb.extension-directory:}") String extensionDirectory,
            @Value("${app.duckdb.threads:2}") int threads,
            @Value("${app.duckdb.preserve-insertion-order:false}") boolean preserveInsertionOrder) {
        this.s3Endpoint = s3Endpoint;
        this.s3AccessKey = s3AccessKey;
        this.s3SecretKey = s3SecretKey;
        this.s3Region = s3Region;
        this.s3UseSsl = s3UseSsl;
        this.memoryLimit = memoryLimit;
        this.tempDirectory = tempDirectory;
        this.extensionDirectory = extensionDirectory;
        this.threads = threads;
        this.preserveInsertionOrder = preserveInsertionOrder;
    }

    @Override
    public <T> long executeStreaming(
            String sql,
            int chunkSize,
            Function<List<String>, T> rowMapper,
            Consumer<List<T>> chunkConsumer) {

        Properties props = new Properties();
        // 결과를 전부 메모리에 올리지 않고 행 단위로 읽어온다
        props.setProperty("jdbc_stream_results", "true");

        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:", props);
             Statement statement = connection.createStatement()) {

            configure(statement);
            log.info("[duckdb-executor] 쿼리 실행 시작");

            try (ResultSet resultSet = statement.executeQuery(sql)) {
                int columnCount = resultSet.getMetaData().getColumnCount();
                List<T> buffer = new ArrayList<>(chunkSize);
                long totalProcessedRows = 0;

                while (resultSet.next()) {
                    // Athena 실행기와 같은 형태(List<String>)로 넘겨서 기존 rowMapper를 그대로 재사용한다
                    List<String> values = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        values.add(resultSet.getString(i));
                    }
                    buffer.add(rowMapper.apply(values));
                    totalProcessedRows++;

                    if (buffer.size() >= chunkSize) {
                        chunkConsumer.accept(new ArrayList<>(buffer));
                        buffer.clear();
                    }
                }
                if (!buffer.isEmpty()) {
                    chunkConsumer.accept(new ArrayList<>(buffer));
                    buffer.clear();
                }

                log.info("[duckdb-executor] 쿼리 완료. 총 처리 rows={}", totalProcessedRows);
                return totalProcessedRows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("DuckDB 쿼리 실패: " + e.getMessage(), e);
        }
    }

    private void configure(Statement statement) throws SQLException {
        statement.execute("SET memory_limit = " + quote(memoryLimit));
        // memory_limit을 넘는 중간 결과는 디스크로 내려 쓴다 (OOM 대신 느려지는 쪽을 택함)
        statement.execute("SET temp_directory = " + quote(tempDirectory));
        // 스레드마다 읽기 버퍼를 따로 잡으므로, 스레드 수가 곧 메모리 사용량에 비례한다.
        // 0이면 DuckDB 기본값(CPU 코어 수). 컨테이너 안에서도 호스트 전체 코어 수를 보고 띄운다
        if (threads > 0) {
            statement.execute("SET threads = " + threads);
        }
        // 최종 결과는 ORDER BY로 정렬하므로 중간 처리 순서를 유지할 필요가 없다. 끄면 버퍼가 줄어든다
        statement.execute("SET preserve_insertion_order = " + preserveInsertionOrder);

        // S3 설정이 없으면 로컬 파일만 읽는다 (단위 테스트용)
        if (s3Endpoint == null || s3Endpoint.isBlank()) {
            return;
        }

        // 이미지에 미리 받아둔 확장을 쓴다. 온프렘은 외부 인터넷이 막혀 있을 수 있다
        if (extensionDirectory != null && !extensionDirectory.isBlank()) {
            statement.execute("SET extension_directory = " + quote(extensionDirectory));
        }

        // 이미지에 미리 설치돼 있으면 INSTALL은 아무 일도 하지 않는다
        statement.execute("INSTALL httpfs");
        statement.execute("LOAD httpfs");
        statement.execute("""
                CREATE OR REPLACE SECRET click_logs (
                    TYPE S3,
                    KEY_ID %s,
                    SECRET %s,
                    REGION %s,
                    ENDPOINT %s,
                    URL_STYLE 'path',
                    USE_SSL %s
                )
                """.formatted(
                quote(s3AccessKey),
                quote(s3SecretKey),
                quote(s3Region),
                quote(s3Endpoint),
                s3UseSsl
        ));
    }

    /** SQL 문자열 리터럴로 감싼다. 값 안의 작은따옴표는 두 번 써서 이스케이프한다. */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}