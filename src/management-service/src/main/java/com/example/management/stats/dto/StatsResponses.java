package com.example.management.stats.dto;

import java.time.LocalDate;
import java.util.List;

public class StatsResponses {

    public record DailyStat(LocalDate date, int clickCount, int visitorCount) {}

    public record DailySummary(long totalClicks, long sumOfDailyVisitors,
                                double avgDailyClicks, int peakClicks, long activeDays) {}

    public record DailyStatsResponse(List<DailyStat> daily, DailySummary summary) {}

    public record LinkCompare(String linkId, String slug, String title,
                               long totalClicks, long sumOfDailyVisitors, int peakDailyClicks) {}

    public record ReferrerStat(String referrerCategory, long clickCount, double percentage) {}
}
