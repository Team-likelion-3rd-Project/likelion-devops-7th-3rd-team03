package com.example.management.links.repository;

import com.example.management.links.domain.Link;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 링크 생성 시 slug 중복을 피하기 위한 최소 DB 접근. */
public interface LinkRepository extends JpaRepository<Link, Long> {

    boolean existsBySlug(String slug);

    long countByUserIdAndIsVisibleTrue(Long userId);

    /** soft-delete된 링크를 제외한 소유자별 목록 조회. 정렬은 호출자가 Pageable로 지정한다. */
    Page<Link> findByUserIdAndIsVisibleTrue(Long userId, Pageable pageable);

    Optional<Link> findByLinkIdAndIsVisibleTrue(String linkId);

    /** 통계 조회 전에 요청한 링크들이 현재 사용자 소유이며 노출 가능한지 한 번에 확인한다. */
    List<Link> findByLinkIdInAndUserIdAndIsVisibleTrue(List<String> linkIds, Long userId);

    /** 단건 통계 조회의 소유권·soft-delete 상태를 함께 확인한다. */
    Optional<Link> findByLinkIdAndUserIdAndIsVisibleTrue(String linkId, Long userId);
}
