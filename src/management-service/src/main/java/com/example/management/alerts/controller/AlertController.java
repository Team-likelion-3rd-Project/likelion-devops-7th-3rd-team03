package com.example.management.alerts.controller;

import com.example.management.alerts.dto.AlertResponses.AlertsResponse;
import com.example.management.alerts.service.AlertService;
import com.example.management.auth.argument.CurrentUser;
import com.example.management.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 팀 알림 (#209). 팀 멤버만 볼 수 있다 (아니면 404 WORKSPACE_NOT_FOUND) */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public ResponseEntity<ApiResponse<AlertsResponse>> getAlerts(@CurrentUser Long userId, @PathVariable String workspaceId) {
        return ResponseEntity.ok(ApiResponse.success(200, alertService.getAlerts(userId, workspaceId), "알림 조회 성공"));
    }
}