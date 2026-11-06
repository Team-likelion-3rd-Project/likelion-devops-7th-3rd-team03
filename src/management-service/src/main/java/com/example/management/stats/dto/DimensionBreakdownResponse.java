package com.example.management.stats.dto;

import java.util.List;

/** 유입경로 / 디바이스 / 지역 분포 응답 (FR-004-4, FR-004-5 공용) */
public record DimensionBreakdownResponse(
        String linkId,
        String dimensionType,
        List<Item> breakdown
) {
    public record Item(String value, int clickCount, double percentage) {}
}
