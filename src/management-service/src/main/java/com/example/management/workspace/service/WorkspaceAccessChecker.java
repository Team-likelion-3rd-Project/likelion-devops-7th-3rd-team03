package com.example.management.workspace.service;

import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.exception.WorkspaceAccessDeniedException;
import com.example.management.workspace.exception.WorkspaceNotFoundException;
import com.example.management.workspace.repository.WorkspaceMemberRepository;
import com.example.management.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 워크스페이스 단위 권한 검사. 워크스페이스 API뿐 아니라 링크·통계·멤버 API에서도 같은 규칙으로 쓴다.
 *   - 없거나, 삭제됐거나, 멤버가 아니면 404 (다른 워크스페이스의 존재 여부를 드러내지 않는다)
 *   - 멤버이지만 관리자가 아니면 403
 * 호출하는 서비스의 트랜잭션 안에서 실행된다.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceAccessChecker {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceAccess requireMember(String workspaceId, Long userId) {
        Workspace workspace = workspaceRepository.findByWorkspaceIdAndStatus(workspaceId, WorkspaceStatus.ACTIVE)
                .orElseThrow(WorkspaceNotFoundException::new);
        WorkspaceMember member = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId)
                .orElseThrow(WorkspaceNotFoundException::new);
        return new WorkspaceAccess(workspace, member);
    }

    public WorkspaceAccess requireAdmin(String workspaceId, Long userId) {
        WorkspaceAccess access = requireMember(workspaceId, userId);
        if (!access.member().isAdmin()) {
            throw new WorkspaceAccessDeniedException();
        }
        return access;
    }
}
