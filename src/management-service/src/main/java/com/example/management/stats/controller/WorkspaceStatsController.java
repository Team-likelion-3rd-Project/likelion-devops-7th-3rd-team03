package com.example.management.stats.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.common.api.ApiResponse;
import com.example.management.stats.domain.LinkDailyDimensionStat.DimensionType;
import com.example.management.stats.dto.WorkspaceStatsResponses.BreakdownResponse;
import com.example.management.stats.dto.WorkspaceStatsResponses.DailyResponse;
import com.example.management.stats.dto.WorkspaceStatsResponses.RealtimeResponse;
import com.example.management.stats.service.WorkspaceStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 워크스페이스 통계 API (#208). 권한은 그 팀의 멤버다.
 * 대상: 없음(팀 전체) / linkIds(링크별) / projectIds(프로젝트별, 프로젝트 기능 구현 때 추가). 각 1~20개.
 * 기존 /api/links/{linkId}/stats/* 는 프론트가 옮길 때까지 남겨 둔다.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stats")
@RequiredArgsConstructor
public class WorkspaceStatsController {

    private static final String SUCCESS = "통계 조회 성공";

    private final WorkspaceStatsService statsService;

    @GetMapping("/daily")
    public ResponseEntity<ApiResponse<DailyResponse>> daily(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @RequestParam(required = false) List<String> linkIds,
            @RequestParam(required = false) List<String> projectIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var data = statsService.getDaily(userId, workspaceId, linkIds, projectIds, from, to);
        return ResponseEntity.ok(ApiResponse.success(200, data, SUCCESS));
    }

    @GetMapping("/breakdown")
    public ResponseEntity<ApiResponse<BreakdownResponse>> breakdown(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @RequestParam(required = false) List<String> linkIds,
            @RequestParam(required = false) List<String> projectIds,
            @RequestParam String type,   // REFERRER / DEVICE / COUNTRY / REGION / OS / BROWSER / HOUR
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DimensionType dimensionType = DimensionType.valueOf(type.toUpperCase(Locale.ROOT));   // 잘못된 값은 400
        var data = statsService.getBreakdown(userId, workspaceId, linkIds, projectIds, dimensionType, from, to);
        return ResponseEntity.ok(ApiResponse.success(200, data, SUCCESS));
    }

    @GetMapping("/realtime")
    public ResponseEntity<ApiResponse<RealtimeResponse>> realtime(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @RequestParam(required = false) List<String> linkIds,
            @RequestParam(required = false) List<String> projectIds) {
        var data = statsService.getRealtime(userId, workspaceId, linkIds, projectIds);
        return ResponseEntity.ok(ApiResponse.success(200, data, SUCCESS));
    }
}