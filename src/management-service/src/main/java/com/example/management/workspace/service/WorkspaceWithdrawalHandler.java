package com.example.management.workspace.service;

import com.example.management.links.service.LinkService;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.domain.WorkspaceRole;
import com.example.management.workspace.repository.WorkspaceMemberRepository;
import com.example.management.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 회원 탈퇴 시 그 사용자가 속한 워크스페이스를 정리한다.
 * - 본인 개인 워크스페이스: 삭제하고 링크를 비활성화한다(초대된 멤버도 더 이상 접근할 수 없다).
 * - 혼자 남은 공유 워크스페이스: 삭제하고 링크를 비활성화한다.
 * - 유일한 관리자인 공유 워크스페이스: 가장 먼저 합류한 멤버에게 관리자를 넘기고 빠진다.
 * - 그 외: 본인 멤버십만 지운다. 그 사람이 만든 링크는 워크스페이스 소유라 남는다.
 * 삭제할 때 멤버 행은 남긴다(WorkspaceService.delete와 같음, 목록·권한 검사에서 status로 걸러진다).
 */
@Component
@RequiredArgsConstructor
public class WorkspaceWithdrawalHandler {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final LinkService linkService;

    /** 회원 상태 변경과 함께 커밋되도록 호출하는 쪽의 트랜잭션 안에서만 실행한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handleWithdrawal(Long userId) {
        for (Long workspaceId : workspaceMemberRepository.findWorkspaceIdsOf(userId, WorkspaceStatus.ACTIVE)) {
            // 목록을 읽은 뒤 다른 요청이 삭제했으면 건너뛴다
            workspaceRepository.findByIdAndStatusForUpdate(workspaceId, WorkspaceStatus.ACTIVE)
                    .ifPresent(workspace -> leave(workspace, userId));
        }
    }

    private void leave(Workspace workspace, Long userId) {
        if (workspace.isPersonal() && userId.equals(workspace.getPersonalOwnerId())) {
            deleteWithLinks(workspace);
            return;
        }

        Optional<WorkspaceMember> member = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), userId);
        if (member.isEmpty()) {
            return; // 잠그기 전에 내보내졌다
        }

        Optional<WorkspaceMember> successor =
                workspaceMemberRepository.findFirstByWorkspaceIdAndUserIdNotOrderByJoinedAtAscIdAsc(workspace.getId(), userId);
        if (successor.isEmpty()) {
            deleteWithLinks(workspace);
            return;
        }

        if (member.get().isAdmin()
                && workspaceMemberRepository.countByWorkspaceIdAndRole(workspace.getId(), WorkspaceRole.ADMIN) == 1) {
            successor.get().changeRole(WorkspaceRole.ADMIN);
        }
        workspaceMemberRepository.delete(member.get());
    }

    private void deleteWithLinks(Workspace workspace) {
        workspace.delete();
        linkService.deactivateAllInWorkspace(workspace.getId());
    }
}