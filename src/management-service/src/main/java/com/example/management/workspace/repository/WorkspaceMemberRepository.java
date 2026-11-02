package com.example.management.workspace.repository;

import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import com.example.management.workspace.domain.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    long countByWorkspaceId(Long workspaceId);

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
}
