package com.example.management.stats.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.stats.domain.LinkDailyDimensionStat;
import com.example.management.stats.dto.DailyChangeResponse;
import com.example.management.stats.dto.DimensionBreakdownResponse;
import com.example.management.stats.dto.StatsResponses.*;
import com.example.management.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/{linkId}/stats/daily")
    public DailyStatsResponse getDailyStats(
            @CurrentUser Long userId,
            @PathVariable String linkId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsService.getDailyStats(userId, linkId, from, to);
    }

    @GetMapping("/stats/compare")
    public List<LinkCompare> compareLinks(
            @CurrentUser Long userId,
            @RequestParam List<String> linkIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsService.compareLinks(userId, linkIds, from, to);
    }

    @GetMapping("/{linkId}/stats/referrers")
    public List<ReferrerStat> getReferrerStats(
            @CurrentUser Long userId,
            @PathVariable String linkId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsService.getReferrerStats(userId, linkId, from, to);
    }


    /** 일별 증감률 조회 (FR-004-2) */
    @GetMapping("/{linkId}/stats/daily-change")
    public DailyChangeResponse getDailyChange(
            @CurrentUser Long userId,
            @PathVariable String linkId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate
    ) {
        LocalDate target = (baseDate != null) ? baseDate : LocalDate.now().minusDays(1);
        return statsService.getDailyChange(userId, linkId, target);
    }

    /** 기타 분포 조회 (FR-004-5 디바이스·지역) */
    @GetMapping("/{linkId}/stats/breakdown")
    public DimensionBreakdownResponse getBreakdown(
            @CurrentUser Long userId,
            @PathVariable String linkId,
            @RequestParam String type,   // DEVICE / REGION
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LinkDailyDimensionStat.DimensionType dimensionType =
                LinkDailyDimensionStat.DimensionType.valueOf(type.toUpperCase());  // ← 여기서 String → enum 변환
        return statsService.getBreakdown(userId, linkId, dimensionType, from, to);
    }


    /** 오늘(KST) 잠정 클릭·UV 조회 — Redis만 조회하고, UV는 HyperLogLog 근사치다. */
    @GetMapping("/{linkId}/stats/realtime")
    public Map<String, Object> getRealtimeCount(@CurrentUser Long userId, @PathVariable String linkId) {
        StatsService.RealtimeStats stats = statsService.getRealtimeStats(userId, linkId);
        return Map.of(
                "linkId", linkId,
                "realtimeClickCount", stats.clickCount(),
                "realtimeUniqueVisitorCount", stats.uniqueVisitorCount()
        );
    }
}
