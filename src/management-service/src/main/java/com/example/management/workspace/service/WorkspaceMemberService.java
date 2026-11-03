package com.example.management.workspace.service;

import com.example.management.auth.domain.User;
import com.example.management.auth.repository.UserRepository;
import com.example.management.workspace.controller.dto.InviteCodeResponse;
import com.example.management.workspace.controller.dto.WorkspaceMemberResponse;
import com.example.management.workspace.controller.dto.WorkspaceResponse;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.domain.WorkspaceRole;
import com.example.management.workspace.exception.AlreadyMemberException;
import com.example.management.workspace.exception.CannotRemoveSelfException;
import com.example.management.workspace.exception.InvalidInviteCodeException;
import com.example.management.workspace.exception.LastAdminException;
import com.example.management.workspace.exception.PersonalWorkspaceLeaveException;
import com.example.management.workspace.exception.PersonalWorkspaceOwnerException;
import com.example.management.workspace.exception.SoleMemberCannotLeaveException;
import com.example.management.workspace.exception.WorkspaceMemberNotFoundException;
import com.example.management.workspace.repository.WorkspaceMemberRepository;
import com.example.management.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 워크스페이스 멤버·초대 코드 유스케이스.
 * 규칙: 워크스페이스마다 관리자는 최소 1명이고, 개인 워크스페이스의 주인은 나가거나 강등·내보내기될 수 없다.
 * 멤버 구성을 바꾸는 작업은 워크스페이스 행을 잠근 뒤 검사해, 동시 요청으로 규칙이 깨지지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceMemberService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final WorkspaceAccessChecker workspaceAccessChecker;
    private final InviteCodeGenerator inviteCodeGenerator;

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getMembers(Long userId, String workspaceId) {
        WorkspaceAccess access = workspaceAccessChecker.requireMember(workspaceId, userId);
        return workspaceMemberRepository.findMembersWithUser(access.workspace().getId()).stream()
                .map(row -> WorkspaceMemberResponse.of(row.member(), row.user(), userId))
                .toList();
    }

    @Transactional
    public WorkspaceMemberResponse changeRole(Long userId, String workspaceId, String targetUserId, WorkspaceRole role) {
        WorkspaceAccess access = workspaceAccessChecker.requireAdminForUpdate(workspaceId, userId);
        Workspace workspace = access.workspace();
        User targetUser = findUser(targetUserId);
        WorkspaceMember target = findMember(workspace, targetUser);

        if (target.getRole() != role && role == WorkspaceRole.MEMBER) {
            if (isPersonalOwner(workspace, target)) {
                throw new PersonalWorkspaceOwnerException();
            }
            if (isLastAdmin(workspace, target)) {
                throw new LastAdminException();
            }
        }

        target.changeRole(role);
        return WorkspaceMemberResponse.of(target, targetUser, userId);
    }

    /** 관리자가 다른 멤버를 내보낸다. 요청한 관리자가 남아 있으므로 관리자가 0명이 되지는 않는다 */
    @Transactional
    public void removeMember(Long userId, String workspaceId, String targetUserId) {
        WorkspaceAccess access = workspaceAccessChecker.requireAdminForUpdate(workspaceId, userId);
        Workspace workspace = access.workspace();
        WorkspaceMember target = findMember(workspace, findUser(targetUserId));

        if (target.getUserId().equals(userId)) {
            throw new CannotRemoveSelfException();
        }
        if (isPersonalOwner(workspace, target)) {
            throw new PersonalWorkspaceOwnerException();
        }

        workspaceMemberRepository.delete(target);
    }

    /**
     * 스스로 나간다. 개인 워크스페이스의 주인, 혼자 남은 멤버(삭제를 안내), 마지막 관리자는 나갈 수 없다.
     * 남의 개인 워크스페이스에 초대된 멤버는 나갈 수 있다.
     */
    @Transactional
    public void leave(Long userId, String workspaceId) {
        WorkspaceAccess access = workspaceAccessChecker.requireMemberForUpdate(workspaceId, userId);
        Workspace workspace = access.workspace();
        WorkspaceMember me = access.member();

        if (isPersonalOwner(workspace, me)) {
            throw new PersonalWorkspaceLeaveException();
        }
        if (workspaceMemberRepository.countByWorkspaceId(workspace.getId()) == 1) {
            throw new SoleMemberCannotLeaveException();
        }
        if (isLastAdmin(workspace, me)) {
            throw new LastAdminException();
        }

        workspaceMemberRepository.delete(me);
    }

    /** 초대 코드를 확인한다. 아직 없으면 발급한다 */
    @Transactional
    public InviteCodeResponse getInviteCode(Long userId, String workspaceId) {
        Workspace workspace = workspaceAccessChecker.requireAdminForUpdate(workspaceId, userId).workspace();
        if (workspace.getInviteCode() == null) {
            workspace.changeInviteCode(inviteCodeGenerator.generateAvailable());
        }
        return new InviteCodeResponse(workspace.getInviteCode());
    }

    /** 초대 코드를 새로 발급한다. 이전 코드로는 더 이상 합류할 수 없다 */
    @Transactional
    public InviteCodeResponse regenerateInviteCode(Long userId, String workspaceId) {
        Workspace workspace = workspaceAccessChecker.requireAdminForUpdate(workspaceId, userId).workspace();
        workspace.changeInviteCode(inviteCodeGenerator.generateAvailable());
        return new InviteCodeResponse(workspace.getInviteCode());
    }

    /** 초대 코드로 합류한다. 멤버로 시작한다 */
    @Transactional
    public WorkspaceResponse join(Long userId, String inviteCode) {
        Workspace workspace = workspaceRepository
                .findByInviteCodeAndStatusForUpdate(InviteCodeGenerator.normalize(inviteCode), WorkspaceStatus.ACTIVE)
                .orElseThrow(InvalidInviteCodeException::new);

        if (workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspace.getId(), userId)) {
            throw new AlreadyMemberException();
        }

        workspaceMemberRepository.save(WorkspaceMember.member(workspace.getId(), userId));
        return WorkspaceResponse.of(workspace, WorkspaceRole.MEMBER);
    }

    private User findUser(String targetUserId) {
        return userRepository.findByUserId(targetUserId)
                .orElseThrow(WorkspaceMemberNotFoundException::new);
    }

    private WorkspaceMember findMember(Workspace workspace, User user) {
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspace.getId(), user.getId())
                .orElseThrow(WorkspaceMemberNotFoundException::new);
    }

    private boolean isPersonalOwner(Workspace workspace, WorkspaceMember member) {
        return workspace.isPersonal() && member.getUserId().equals(workspace.getPersonalOwnerId());
    }

    private boolean isLastAdmin(Workspace workspace, WorkspaceMember member) {
        return member.isAdmin()
                && workspaceMemberRepository.countByWorkspaceIdAndRole(workspace.getId(), WorkspaceRole.ADMIN) == 1;
    }
}