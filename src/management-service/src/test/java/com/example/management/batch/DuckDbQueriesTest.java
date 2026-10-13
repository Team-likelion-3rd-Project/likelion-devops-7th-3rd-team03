package com.example.management.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대상일: 2026-10-13 (KST). KST 하루 = UTC 10/12 15:00 ~ 10/13 14:59:59
 *
 * 포함 (봇 아님 + KST 10/13)
 *  - link1 v9  UTC 10/12 15:30 (KST 10/13 00:30)  전날 UTC 파티션
 *  - link1 v1  UTC 10/13 01:00
 *  - link1 v1  UTC 10/13 02:00  (같은 방문자 재방문)
 *  - link2 v3  UTC 10/13 03:00
 * 제외
 *  - link1 v8  UTC 10/12 14:59:59 (KST 10/12 23:59:59)  날짜 경계 직전
 *  - link1 봇  UTC 10/12 16:00    전날 파티션의 봇 ← Athena 괄호 버그가 놓치던 경우
 *  - link2 봇  UTC 10/13 04:00
 *  - link1 v7  UTC 10/13 15:00 (KST 10/14 00:00)        날짜 경계 직후
 *
 * 정답: link1 클릭 3 / 방문자 2(v9, v1), link2 클릭 1 / 방문자 1
 */
class DuckDbQueriesTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 13);

    @TempDir
    Path base;

    @BeforeEach
    void setUp() throws IOException {
        // 전날 UTC 파티션 (일반 JSON)
        write("year=2026/month=10/day=12/part-a.json", false,
                event(1, "2026-10-12T15:30:00.123456789", "v9", "ko-KR", "DESKTOP", "DIRECT", false),
                event(1, "2026-10-12T14:59:59", "v8", "ko-KR", "DESKTOP", "DIRECT", false),
                event(1, "2026-10-12T16:00:00", "bot", "ko-KR", "DESKTOP", "DIRECT", true));

        // 당일 UTC 파티션 (운영과 같은 gzip)
        write("year=2026/month=10/day=13/part-b.json.gz", true,
                event(1, "2026-10-13T01:00:00.5", "v1", "en", "MOBILE", "SEARCH", false),
                event(1, "2026-10-13T02:00:00", "v1", null, "MOBILE", "SOCIAL", false),
                event(2, "2026-10-13T03:00:00", "v3", "ja-JP", "TABLET", "DIRECT", false),
                event(2, "2026-10-13T04:00:00", "bot", "ja-JP", "DESKTOP", "DIRECT", true),
                event(1, "2026-10-13T15:00:00", "v7", "ko-KR", "DESKTOP", "DIRECT", false));
    }

    @Test
    void 일별_통계는_봇과_KST_날짜_밖의_클릭을_제외한다() {
        List<List<String>> rows = run(queries().dailyStats(TARGET));

        assertThat(rows).containsExactly(
                List.of("1", "2026-10-13", "3", "2"),
                List.of("2", "2026-10-13", "1", "1")
        );
    }

    @Test
    void 기기별_통계() {
        List<List<String>> rows = run(queries().dimensionStats(TARGET, "DEVICE"));

        assertThat(rows).containsExactly(
                List.of("1", "2026-10-13", "DESKTOP", "1"),
                List.of("1", "2026-10-13", "MOBILE", "2"),
                List.of("2", "2026-10-13", "TABLET", "1")
        );
    }

    @Test
    void 지역별_통계는_국가코드가_없으면_UNKNOWN으로_묶는다() {
        // v9 "ko-KR" -> KR, v1 "en" -> 국가코드 없음, v1 null -> 없음, v3 "ja-JP" -> JP
        List<List<String>> rows = run(queries().dimensionStats(TARGET, "REGION"));

        assertThat(rows).containsExactly(
                List.of("1", "2026-10-13", "KR", "1"),
                List.of("1", "2026-10-13", "UNKNOWN", "2"),
                List.of("2", "2026-10-13", "JP", "1")
        );
    }

    @Test
    void 유입경로별_통계() {
        List<List<String>> rows = run(queries().dimensionStats(TARGET, "REFERRER"));

        assertThat(rows).containsExactly(
                List.of("1", "2026-10-13", "DIRECT", "1"),
                List.of("1", "2026-10-13", "SEARCH", "1"),
                List.of("1", "2026-10-13", "SOCIAL", "1"),
                List.of("2", "2026-10-13", "DIRECT", "1")
        );
    }

    private DuckDbQueries queries() {
        return new DuckDbQueries(base.toString().replace('\\', '/'));
    }

    private List<List<String>> run(String sql) {
        DuckDbQueryExecutor executor = new DuckDbQueryExecutor(
                "", "", "", "us-east-1", false, "256MB", base.resolve("duckdb-tmp").toString(), "");
        List<List<String>> rows = new ArrayList<>();
        executor.executeStreaming(sql, 100, values -> values, rows::addAll);
        return rows;
    }

    private static String event(long linkId, String clickedAt, String visitorId, String language,
                                String deviceType, String referrerCategory, boolean isBot) {
        String lang = language == null ? "null" : "\"" + language + "\"";
        return String.format(
                "{\"event_id\":\"e\",\"schema_version\":1,\"link_id\":%d,\"clicked_at\":\"%s\","
                        + "\"visitor_id\":\"%s\",\"language\":%s,\"device_type\":\"%s\","
                        + "\"referrer_category\":\"%s\",\"is_bot\":%s}",
                linkId, clickedAt, visitorId, lang, deviceType, referrerCategory, isBot);
    }

    private void write(String relativePath, boolean gzip, String... lines) throws IOException {
        Path file = base.resolve(relativePath);
        Files.createDirectories(file.getParent());
        byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
                out.write(content);
            }
        } else {
            Files.write(file, content);
        }
    }
}