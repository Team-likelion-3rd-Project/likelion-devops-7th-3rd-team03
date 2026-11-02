package com.example.management.workspace.service;

import com.example.management.links.service.LinkService;
import com.example.management.workspace.controller.dto.WorkspaceResponse;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.domain.WorkspaceRole;
import com.example.management.workspace.exception.PersonalWorkspaceUndeletableException;
import com.example.management.workspace.exception.WorkspaceHasMembersException;
import com.example.management.workspace.repository.WorkspaceMemberRepository;
import com.example.management.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 워크스페이스 생성·조회·이름 변경·삭제 유스케이스. 멤버·초대 코드는 별도 이슈(#201)에서 다룬다. */
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final LinkService linkService;

    /** 가입할 때 개인 워크스페이스를 만들고 주인을 관리자로 등록한다. 회원 저장과 같은 트랜잭션에서 호출된다 */
    @Transactional
    public Workspace createPersonal(Long userId) {
        Workspace workspace = workspaceRepository.save(Workspace.personal(userId));
        workspaceMemberRepository.save(WorkspaceMember.admin(workspace.getId(), userId));
        return workspace;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getMyWorkspaces(Long userId) {
        return workspaceMemberRepository.findWorkspacesWithRole(userId, WorkspaceStatus.ACTIVE).stream()
                .map(row -> WorkspaceResponse.of(row.workspace(), row.role()))
                .toList();
    }

    @Transactional
    public WorkspaceResponse create(Long userId, String name) {
        Workspace workspace = workspaceRepository.save(Workspace.shared(name.strip()));
        workspaceMemberRepository.save(WorkspaceMember.admin(workspace.getId(), userId));
        return WorkspaceResponse.of(workspace, WorkspaceRole.ADMIN);
    }

    /** 개인 워크스페이스도 이름은 바꿀 수 있다 */
    @Transactional
    public WorkspaceResponse rename(Long userId, String workspaceId, String name) {
        WorkspaceAccess access = workspaceAccessChecker.requireAdmin(workspaceId, userId);
        access.workspace().rename(name.strip());
        return WorkspaceResponse.of(access.workspace(), access.member().getRole());
    }

    /**
     * 워크스페이스를 soft delete하고 소속 링크를 모두 비활성화한다.
     * 개인 워크스페이스, 그리고 요청한 관리자 외에 다른 멤버가 남아 있는 워크스페이스는 삭제할 수 없다.
     * 멤버 행은 남겨 둔다(삭제된 워크스페이스는 목록·권한 검사에서 status로 걸러진다).
     */
    @Transactional
    public void delete(Long userId, String workspaceId) {
        WorkspaceAccess access = workspaceAccessChecker.requireAdmin(workspaceId, userId);
        Workspace workspace = access.workspace();

        if (workspace.isPersonal()) {
            throw new PersonalWorkspaceUndeletableException();
        }
        if (workspaceMemberRepository.countByWorkspaceId(workspace.getId()) > 1) {
            throw new WorkspaceHasMembersException();
        }

        workspace.delete();
        linkService.deactivateAllInWorkspace(workspace.getId());
    }
}
