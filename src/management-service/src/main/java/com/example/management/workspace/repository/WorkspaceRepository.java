package com.example.management.workspace.repository;

import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /** 외부 노출용 UUID로 찾는다. 삭제된 워크스페이스를 제외하려면 status에 ACTIVE를 넘긴다 */
    Optional<Workspace> findByWorkspaceIdAndStatus(String workspaceId, WorkspaceStatus status);
}
