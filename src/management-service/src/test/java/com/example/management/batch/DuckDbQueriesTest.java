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
 *  - link1 v9  UTC 10/12 15:30 (KST 00시)  전날 UTC 파티션, 스키마 1
 *  - link1 v1  UTC 10/13 01:00 (KST 10시)  스키마 1
 *  - link1 v1  UTC 10/13 02:00 (KST 11시)  같은 방문자 재방문, 스키마 1
 *  - link2 v3  UTC 10/13 03:00 (KST 12시)  스키마 2 (CloudFront country·region 있음)
 * 제외
 *  - link1 v8  UTC 10/12 14:59:59 (KST 10/12 23:59:59)  날짜 경계 직전
 *  - link1 봇  UTC 10/12 16:00    전날 파티션의 봇
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
                v1(1, "2026-10-12T15:30:00.123456789", "v9", "ko-KR", "DESKTOP", "Windows", "Chrome", "DIRECT", false),
                v1(1, "2026-10-12T14:59:59", "v8", "ko-KR", "DESKTOP", "Windows", "Chrome", "DIRECT", false),
                v1(1, "2026-10-12T16:00:00", "bot", "ko-KR", "DESKTOP", "Linux", "Curl", "DIRECT", true));

        // 당일 UTC 파티션 (운영과 같은 gzip, 스키마 1·2 섞임)
        write("year=2026/month=10/day=13/part-b.json.gz", true,
                v1(1, "2026-10-13T01:00:00.5", "v1", "en", "MOBILE", "iOS", "Safari", "SEARCH", false),
                v1(1, "2026-10-13T02:00:00", "v1", null, "MOBILE", "iOS", "Safari", "SOCIAL", false),
                v2(2, "2026-10-13T03:00:00", "v3", "ja-JP", "TABLET", "Android", "Chrome", "DIRECT", false, "KR", "11"),
                v1(2, "2026-10-13T04:00:00", "bot", "ja-JP", "DESKTOP", "Linux", "Curl", "DIRECT", true),
                v1(1, "2026-10-13T15:00:00", "v7", "ko-KR", "DESKTOP", "Windows", "Chrome", "DIRECT", false));
    }

    @Test
    void 일별_통계는_봇과_KST_날짜_밖의_클릭을_제외한다() {
        assertThat(run(queries().dailyStats(TARGET))).containsExactly(
                List.of("1", "2026-10-13", "3", "2"),
                List.of("2", "2026-10-13", "1", "1"));
    }

    @Test
    void 기기별_통계() {
        assertThat(run(queries().dimensionStats(TARGET, "DEVICE"))).containsExactly(
                List.of("1", "2026-10-13", "DESKTOP", "1"),
                List.of("1", "2026-10-13", "MOBILE", "2"),
                List.of("2", "2026-10-13", "TABLET", "1"));
    }

    @Test
    void 유입경로별_통계() {
        assertThat(run(queries().dimensionStats(TARGET, "REFERRER"))).containsExactly(
                List.of("1", "2026-10-13", "DIRECT", "1"),
                List.of("1", "2026-10-13", "SEARCH", "1"),
                List.of("1", "2026-10-13", "SOCIAL", "1"),
                List.of("2", "2026-10-13", "DIRECT", "1"));
    }

    @Test
    void 운영체제별_통계() {
        assertThat(run(queries().dimensionStats(TARGET, "OS"))).containsExactly(
                List.of("1", "2026-10-13", "Windows", "1"),
                List.of("1", "2026-10-13", "iOS", "2"),
                List.of("2", "2026-10-13", "Android", "1"));
    }

    @Test
    void 브라우저별_통계() {
        assertThat(run(queries().dimensionStats(TARGET, "BROWSER"))).containsExactly(
                List.of("1", "2026-10-13", "Chrome", "1"),
                List.of("1", "2026-10-13", "Safari", "2"),
                List.of("2", "2026-10-13", "Chrome", "1"));
    }

    @Test
    void 시간대별_통계는_KST_시각이다() {
        // UTC 15:30 -> KST 00시, 01:00 -> 10시, 02:00 -> 11시, 03:00 -> 12시
        assertThat(run(queries().dimensionStats(TARGET, "HOUR"))).containsExactly(
                List.of("1", "2026-10-13", "00", "1"),
                List.of("1", "2026-10-13", "10", "1"),
                List.of("1", "2026-10-13", "11", "1"),
                List.of("2", "2026-10-13", "12", "1"));
    }

    @Test
    void 국가는_CloudFront_값을_먼저_쓰고_없으면_언어_태그로_대체한다() {
        // v9 "ko-KR" -> KR, v1 "en" -> 없음, v1 null -> 없음
        // v3 는 언어가 ja-JP 지만 CloudFront country(KR) 가 우선
        assertThat(run(queries().dimensionStats(TARGET, "COUNTRY"))).containsExactly(
                List.of("1", "2026-10-13", "KR", "1"),
                List.of("1", "2026-10-13", "UNKNOWN", "2"),
                List.of("2", "2026-10-13", "KR", "1"));
    }

    @Test
    void 지역은_CloudFront_시도_코드만_쓰고_없으면_UNKNOWN() {
        // 스키마 1 이벤트와 온프렘은 region 이 없다
        assertThat(run(queries().dimensionStats(TARGET, "REGION"))).containsExactly(
                List.of("1", "2026-10-13", "UNKNOWN", "3"),
                List.of("2", "2026-10-13", "11", "1"));
    }

    private DuckDbQueries queries() {
        return new DuckDbQueries(base.toString().replace('\\', '/'), true);
    }

    private List<List<String>> run(String sql) {
        DuckDbQueryExecutor executor = DuckDbQueryExecutor.localOnly(base.resolve("duckdb-tmp").toString(), 1, 0, 0);
        List<List<String>> rows = new ArrayList<>();
        executor.executeStreaming(sql, 100, values -> values, rows::addAll);
        return rows;
    }

    /** 스키마 1 이벤트 (country·region 필드 없음) */
    private static String v1(long linkId, String clickedAt, String visitorId, String language, String deviceType,
                             String os, String browser, String referrerCategory, boolean isBot) {
        return String.format(
                "{\"event_id\":\"e\",\"schema_version\":1,\"link_id\":%d,\"clicked_at\":\"%s\","
                        + "\"visitor_id\":\"%s\",\"language\":%s,\"device_type\":\"%s\","
                        + "\"operating_system\":\"%s\",\"browser\":\"%s\","
                        + "\"referrer_category\":\"%s\",\"is_bot\":%s}",
                linkId, clickedAt, visitorId, quote(language), deviceType, os, browser, referrerCategory, isBot);
    }

    /** 스키마 2 이벤트 (#207 country·region 포함) */
    private static String v2(long linkId, String clickedAt, String visitorId, String language, String deviceType,
                             String os, String browser, String referrerCategory, boolean isBot,
                             String country, String region) {
        String base = v1(linkId, clickedAt, visitorId, language, deviceType, os, browser, referrerCategory, isBot);
        return base.replace("\"schema_version\":1", "\"schema_version\":2")
                .replace("}", ",\"country\":" + quote(country) + ",\"region\":" + quote(region) + "}");
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
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