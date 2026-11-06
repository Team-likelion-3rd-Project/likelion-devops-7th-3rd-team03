package com.example.management.links.repository;

import com.example.management.links.domain.Link;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 링크 생성 시 slug 중복을 피하기 위한 최소 DB 접근. */
public interface LinkRepository extends JpaRepository<Link, Long> {

    boolean existsBySlug(String slug);

    long countByWorkspaceIdAndIsVisibleTrue(Long workspaceId);

    /** soft-delete된 링크를 제외한 워크스페이스별 목록 조회. 정렬은 호출자가 Pageable로 지정한다. */
    Page<Link> findByWorkspaceIdAndIsVisibleTrue(Long workspaceId, Pageable pageable);

    /** 수정·삭제 대상 조회. 다른 워크스페이스의 링크는 찾지 못한다. */
    Optional<Link> findByLinkIdAndWorkspaceIdAndIsVisibleTrue(String linkId, Long workspaceId);

    /** 통계 조회 전에 요청한 링크들이 현재 사용자 소유이며 노출 가능한지 한 번에 확인한다. */
    List<Link> findByLinkIdInAndUserIdAndIsVisibleTrue(List<String> linkIds, Long userId);

    /** 단건 통계 조회의 소유권·soft-delete 상태를 함께 확인한다. */
    Optional<Link> findByLinkIdAndUserIdAndIsVisibleTrue(String linkId, Long userId);

    /** 워크스페이스를 삭제할 때 비활성화할 링크 */
    List<Link> findByWorkspaceIdAndIsVisibleTrue(Long workspaceId);

    /** 통계 대상 링크 확인. 다른 워크스페이스·soft-delete 된 링크는 찾지 못한다 */
    List<Link> findByLinkIdInAndWorkspaceIdAndIsVisibleTrue(Collection<String> linkIds, Long workspaceId);
}
