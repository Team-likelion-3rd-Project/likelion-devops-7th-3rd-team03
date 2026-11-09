package com.example.management.stats.repository;

import com.example.management.stats.domain.LinkDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface LinkDailyStatRepository extends JpaRepository<LinkDailyStat, Long> {

    /** 묶음 수와 상관없이 한 번에 읽고, 묶음별 합산은 서비스에서 한다 */
    @Query(value = """
        SELECT s.link_id       AS linkId,
               s.stat_date     AS statDate,
               s.click_count   AS clickCount,
               s.visitor_count AS visitorCount
        FROM link_daily_stats s
        WHERE s.link_id IN (:linkIds) AND s.stat_date BETWEEN :from AND :to
        """, nativeQuery = true)
    List<LinkDailyRow> findDailyRows(@Param("linkIds") Collection<Long> linkIds,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    interface LinkDailyRow {
        Long getLinkId();
        LocalDate getStatDate();
        Integer getClickCount();
        Integer getVisitorCount();
    }

    /** 알림(방문자 급증)용. 링크별 기간 클릭 합계를 한 번에 읽는다 */
    @Query(value = """
        SELECT s.link_id AS linkId, SUM(s.click_count) AS clickCount
        FROM link_daily_stats s
        WHERE s.link_id IN (:linkIds) AND s.stat_date BETWEEN :from AND :to
        GROUP BY s.link_id
        """, nativeQuery = true)
    List<LinkClickSumRow> sumClicksByLink(@Param("linkIds") Collection<Long> linkIds,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    interface LinkClickSumRow {
        Long getLinkId();
        Long getClickCount();
    }
}

