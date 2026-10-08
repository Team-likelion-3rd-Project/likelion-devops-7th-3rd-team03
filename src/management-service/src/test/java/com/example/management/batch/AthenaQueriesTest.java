package com.example.management.batch;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AthenaQueriesTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 8);

    // 두 파티션 조건 전체가 괄호로 묶인 뒤에 is_bot 필터가 붙어야 한다
    private static final String EXPECTED_WHERE =
            "WHERE (((year='2026' AND month='10' AND day='07')) OR ((year='2026' AND month='10' AND day='08')))"
                    + " AND is_bot = false";

    private final AthenaQueries queries = new AthenaQueries();

    @Test
    void dailyStats_봇_필터가_두_파티션_모두에_적용된다() {
        String sql = normalize(queries.dailyStats(TARGET));

        assertThat(sql).contains(EXPECTED_WHERE);
    }

    @Test
    void dimensionStats_봇_필터가_두_파티션_모두에_적용된다() {
        for (String dimensionType : new String[]{"REFERRER", "DEVICE", "REGION"}) {
            String sql = normalize(queries.dimensionStats(TARGET, dimensionType));

            assertThat(sql)
                    .as("dimensionType=%s", dimensionType)
                    .contains(EXPECTED_WHERE);
        }
    }

    // 텍스트 블록의 줄바꿈/들여쓰기를 공백 하나로 합쳐서 비교한다
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ");
    }
}