package com.example.management.stats.dto;

import java.time.LocalDate;
import java.util.List;

/** 워크스페이스 통계 응답 (#208). 대상과 상관없이 항상 groups 로 감싼다. linkId·projectId 가 둘 다 null 이면 팀 전체 묶음 */
public final class WorkspaceStatsResponses {

    private WorkspaceStatsResponses() {
    }

    public record DailyResponse(List<DailyGroup> groups) {}

    public record DailyGroup(String linkId, String projectId, List<DailyPoint> daily, Summary summary) {}

    public record DailyPoint(LocalDate date, long clickCount, long visitorCount) {}

    public record Summary(long totalClicks, long sumOfDailyVisitors, double avgDailyClicks,
                          long peakClicks, long activeDays) {}

    public record BreakdownResponse(String type, List<BreakdownGroup> groups) {}

    public record BreakdownGroup(String linkId, String projectId, List<BreakdownItem> breakdown) {}

    public record BreakdownItem(String value, long clickCount, double percentage) {}

    public record RealtimeResponse(List<RealtimeGroup> groups) {}

    public record RealtimeGroup(String linkId, String projectId, long clickCount, long uniqueVisitorCount) {}
}