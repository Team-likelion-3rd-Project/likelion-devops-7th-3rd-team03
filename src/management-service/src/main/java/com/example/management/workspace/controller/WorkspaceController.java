package com.example.management.workspace.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.common.api.ApiResponse;
import com.example.management.workspace.controller.dto.WorkspaceNameRequest;
import com.example.management.workspace.controller.dto.WorkspaceResponse;
import com.example.management.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 워크스페이스 관리 API. 경로의 workspaceId는 외부 노출용 UUID다. */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkspaceResponse>>> getMyWorkspaces(@CurrentUser Long userId) {
        List<WorkspaceResponse> response = workspaceService.getMyWorkspaces(userId);
        return ResponseEntity.ok(ApiResponse.success(200, response, "워크스페이스 목록 조회 성공"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> create(
            @CurrentUser Long userId,
            @Valid @RequestBody WorkspaceNameRequest request
    ) {
        WorkspaceResponse response = workspaceService.create(userId, request.name());
        return ResponseEntity.ok(ApiResponse.success(200, response, "워크스페이스가 생성되었습니다."));
    }

    @PatchMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> rename(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @Valid @RequestBody WorkspaceNameRequest request
    ) {
        WorkspaceResponse response = workspaceService.rename(userId, workspaceId, request.name());
        return ResponseEntity.ok(ApiResponse.success(200, response, "워크스페이스 이름이 변경되었습니다."));
    }

    @DeleteMapping("/{workspaceId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @CurrentUser Long userId,
            @PathVariable String workspaceId
    ) {
        workspaceService.delete(userId, workspaceId);
        return ResponseEntity.ok(ApiResponse.success(200, null, "워크스페이스가 삭제되었습니다."));
    }
}
