package com.example.management.workspace.controller;

import com.example.management.auth.argument.CurrentUser;
import com.example.management.common.api.ApiResponse;
import com.example.management.workspace.controller.dto.ChangeRoleRequest;
import com.example.management.workspace.controller.dto.InviteCodeResponse;
import com.example.management.workspace.controller.dto.JoinWorkspaceRequest;
import com.example.management.workspace.controller.dto.WorkspaceMemberResponse;
import com.example.management.workspace.controller.dto.WorkspaceResponse;
import com.example.management.workspace.service.WorkspaceMemberService;
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

/** 워크스페이스 멤버·초대 코드 API. 경로의 workspaceId·userId는 외부 노출용 UUID다. */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceMemberController {

    private final WorkspaceMemberService workspaceMemberService;

    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<ApiResponse<List<WorkspaceMemberResponse>>> getMembers(
            @CurrentUser Long userId,
            @PathVariable String workspaceId
    ) {
        List<WorkspaceMemberResponse> response = workspaceMemberService.getMembers(userId, workspaceId);
        return ResponseEntity.ok(ApiResponse.success(200, response, "멤버 목록 조회 성공"));
    }

    @PatchMapping("/{workspaceId}/members/{targetUserId}")
    public ResponseEntity<ApiResponse<WorkspaceMemberResponse>> changeRole(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @PathVariable String targetUserId,
            @Valid @RequestBody ChangeRoleRequest request
    ) {
        WorkspaceMemberResponse response = workspaceMemberService.changeRole(userId, workspaceId, targetUserId, request.role());
        return ResponseEntity.ok(ApiResponse.success(200, response, "멤버 역할이 변경되었습니다."));
    }

    @DeleteMapping("/{workspaceId}/members/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @CurrentUser Long userId,
            @PathVariable String workspaceId,
            @PathVariable String targetUserId
    ) {
        workspaceMemberService.removeMember(userId, workspaceId, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(200, null, "멤버를 내보냈습니다."));
    }

    @PostMapping("/{workspaceId}/leave")
    public ResponseEntity<ApiResponse<Void>> leave(
            @CurrentUser Long userId,
            @PathVariable String workspaceId
    ) {
        workspaceMemberService.leave(userId, workspaceId);
        return ResponseEntity.ok(ApiResponse.success(200, null, "워크스페이스에서 나갔습니다."));
    }

    @GetMapping("/{workspaceId}/invite-code")
    public ResponseEntity<ApiResponse<InviteCodeResponse>> getInviteCode(
            @CurrentUser Long userId,
            @PathVariable String workspaceId
    ) {
        InviteCodeResponse response = workspaceMemberService.getInviteCode(userId, workspaceId);
        return ResponseEntity.ok(ApiResponse.success(200, response, "초대 코드 조회 성공"));
    }

    @PostMapping("/{workspaceId}/invite-code")
    public ResponseEntity<ApiResponse<InviteCodeResponse>> regenerateInviteCode(
            @CurrentUser Long userId,
            @PathVariable String workspaceId
    ) {
        InviteCodeResponse response = workspaceMemberService.regenerateInviteCode(userId, workspaceId);
        return ResponseEntity.ok(ApiResponse.success(200, response, "초대 코드가 재발급되었습니다."));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<WorkspaceResponse>> join(
            @CurrentUser Long userId,
            @Valid @RequestBody JoinWorkspaceRequest request
    ) {
        WorkspaceResponse response = workspaceMemberService.join(userId, request.inviteCode());
        return ResponseEntity.ok(ApiResponse.success(200, response, "워크스페이스에 참여했습니다."));
    }
}