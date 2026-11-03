package com.example.management.workspace.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.Workspace.WorkspaceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /** 외부 노출용 UUID로 찾는다. 삭제된 워크스페이스를 제외하려면 status에 ACTIVE를 넘긴다 */
    Optional<Workspace> findByWorkspaceIdAndStatus(String workspaceId, WorkspaceStatus status);

    /**
     * 멤버 구성을 바꾸기 전에 워크스페이스 행을 잠근다(SELECT ... FOR UPDATE).
     * 관리자 두 명이 동시에 서로를 강등하는 것처럼 검사와 변경 사이에 다른 요청이 끼어드는 것을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workspace w WHERE w.workspaceId = :workspaceId AND w.status = :status")
    Optional<Workspace> findByWorkspaceIdAndStatusForUpdate(@Param("workspaceId") String workspaceId,
                                                            @Param("status") WorkspaceStatus status);

    /** 초대 코드로 합류할 때 잠그고 찾는다. 같은 사용자의 중복 합류와 삭제 중인 워크스페이스 합류를 막는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workspace w WHERE w.inviteCode = :inviteCode AND w.status = :status")
    Optional<Workspace> findByInviteCodeAndStatusForUpdate(@Param("inviteCode") String inviteCode,
                                                           @Param("status") WorkspaceStatus status);

    boolean existsByInviteCode(String inviteCode);
}
