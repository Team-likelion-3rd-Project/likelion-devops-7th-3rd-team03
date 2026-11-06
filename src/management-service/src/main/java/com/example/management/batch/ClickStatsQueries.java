package com.example.management.batch;

import java.time.LocalDate;

/** 엔진별 집계 SQL 제공자. 반환 컬럼 순서는 DailyStatRow / DimensionStatRow.from()과 맞춘다. */
interface ClickStatsQueries {

    String dailyStats(LocalDate targetDateKst);

    String dimensionStats(LocalDate targetDateKst, String dimensionType);
}