package com.example.management.workspace.repository;

import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.domain.WorkspaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    long countByWorkspaceId(Long workspaceId);

    long countByWorkspaceIdAndRole(Long workspaceId, WorkspaceRole role);

    /** 관리자를 넘겨받을 사람: 본인을 뺀 멤버 중 가장 먼저 합류한 사람 */
    Optional<WorkspaceMember> findFirstByWorkspaceIdAndUserIdNotOrderByJoinedAtAscIdAsc(Long workspaceId, Long userId);

    /**
     * 사용자가 속한 워크스페이스의 내부 ID. 회원 탈퇴 처리는 이 순서(id 오름차순)대로 잠가
     * 동시에 실행되는 다른 탈퇴와 잠금 순서가 엇갈리지 않게 한다.
     */
    @Query("""
            SELECT w.id FROM WorkspaceMember m JOIN Workspace w ON w.id = m.workspaceId
             WHERE m.userId = :userId AND w.status = :status
             ORDER BY w.id ASC
            """)
    List<Long> findWorkspaceIdsOf(@Param("userId") Long userId, @Param("status") WorkspaceStatus status);

    /**
     * 사용자가 속한 워크스페이스와 그 안에서의 역할. 엔티티끼리 연관관계가 없어 내부 ID로 조인한다.
     * 개인 워크스페이스를 맨 위에, 나머지는 합류한 순서로 정렬한다.
     */
    @Query("""
            SELECT new com.example.management.workspace.repository.WorkspaceWithRole(w, m.role)
              FROM WorkspaceMember m JOIN Workspace w ON w.id = m.workspaceId
             WHERE m.userId = :userId AND w.status = :status
             ORDER BY w.isPersonal DESC, m.joinedAt ASC, w.id ASC
            """)
    List<WorkspaceWithRole> findWorkspacesWithRole(@Param("userId") Long userId, @Param("status") WorkspaceStatus status);

    /**
     * 워크스페이스의 멤버와 사용자 정보. 관리자를 먼저, 그다음 합류한 순서로 정렬한다
     * (role은 문자열로 저장되어 'ADMIN' < 'MEMBER' 순서가 된다).
     */
    @Query("""
            SELECT new com.example.management.workspace.repository.WorkspaceMemberWithUser(m, u)
              FROM WorkspaceMember m JOIN User u ON u.id = m.userId
             WHERE m.workspaceId = :workspaceId
             ORDER BY m.role ASC, m.joinedAt ASC, m.id ASC
            """)
    List<WorkspaceMemberWithUser> findMembersWithUser(@Param("workspaceId") Long workspaceId);
}
