package com.example.management.stats.dto;

import java.time.LocalDate;

/**
 * 일별 증감률 응답 (FR-004-2)
 * 기준: 어제(baseDay) vs 그제(previousDay) — 오늘 값은 배치 전이라 미확정이므로 비교에서 제외.
 */
public record DailyChangeResponse(
        String linkId,
        LocalDate baseDate,
        MetricChange clicks,
        MetricChange visitors
) {
    public record MetricChange(int base, int previous, double changeRate) {

        public static MetricChange of(int base, int previous) {
            double rate = previous == 0
                    ? (base == 0 ? 0.0 : 100.0)   // 그제가 0이었다면 증가율은 100%로 표기(0으로 나누기 방지)
                    : ((double) (base - previous) / previous) * 100.0;
            return new MetricChange(base, previous, Math.round(rate * 10) / 10.0);
        }
    }
}
