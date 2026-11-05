package com.example.management.batch;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.*;

/**
 * 청크의 link_id 중 links 에 실제로 있는 것만 고른다 (#253).
 * 링크가 실제로 지워지면 S3 에 남은 클릭이 외래키(1452)에 걸리므로, upsert 전에 미리 걸러 bisect 가 일어나지 않게 한다.
 * 조회와 upsert 사이에 링크가 지워지는 경우는 남지만 드물고, 데이터 오류는 재시도하지 않아(#252) bisect 만 짧게 돈다.
 */
final class ExistingLinks {

    static final String SQL = "SELECT id FROM links WHERE id IN (:ids)";
    static final int SAMPLE_SIZE = 20;

    private ExistingLinks() {
    }

    /** 청크는 최대 chunkSize(1000)행이라 IN 도 최대 1000개다 */
    static Set<Long> of(NamedParameterJdbcTemplate jdbcTemplate, Set<Long> linkIds) {
        if (linkIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbcTemplate.queryForList(SQL, Map.of("ids", linkIds), Long.class));
    }

    static List<Long> sample(Collection<Long> ids) {
        return ids.stream().limit(SAMPLE_SIZE).toList();
    }
}