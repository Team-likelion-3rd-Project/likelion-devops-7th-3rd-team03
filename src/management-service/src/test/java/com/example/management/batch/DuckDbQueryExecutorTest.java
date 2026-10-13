package com.example.management.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbQueryExecutorTest {

    @TempDir
    Path tempDir;

    private DuckDbQueryExecutor executor() {
        // S3 설정을 비워서 로컬 파일만 읽는다 (네트워크 불필요)
        return new DuckDbQueryExecutor("", "", "", "us-east-1", false, "256MB", tempDir.toString());
    }

    @Test
    void 결과를_chunkSize_단위로_나눠서_전달한다() {
        List<List<List<String>>> chunks = new ArrayList<>();

        long total = executor().executeStreaming(
                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) t(id, name) ORDER BY id",
                2,
                values -> values,
                chunks::add
        );

        assertThat(total).isEqualTo(3);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).containsExactly(List.of("1", "a"), List.of("2", "b"));
        assertThat(chunks.get(1)).containsExactly(List.of("3", "c"));
    }

    @Test
    void 줄단위_JSON_파일을_읽어_집계한다() throws Exception {
        Path file = tempDir.resolve("events.json");
        Files.writeString(file, """
                {"link_id":1,"is_bot":false}
                {"link_id":1,"is_bot":false}
                {"link_id":2,"is_bot":true}
                """);
        String path = file.toString().replace('\\', '/');
        List<List<String>> rows = new ArrayList<>();

        executor().executeStreaming(
                "SELECT link_id, COUNT(*) FROM read_json('" + path + "') WHERE is_bot = false GROUP BY link_id",
                100,
                values -> values,
                rows::addAll
        );

        assertThat(rows).containsExactly(List.of("1", "2"));
    }
}