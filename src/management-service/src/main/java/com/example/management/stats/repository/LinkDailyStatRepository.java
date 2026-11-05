package com.example.management.stats.repository;

import com.example.management.stats.domain.LinkDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LinkDailyStatRepository extends JpaRepository<LinkDailyStat, Long> {

    @Query(value = """
        WITH RECURSIVE date_axis (d) AS (
            SELECT CAST(:from AS DATE)
            UNION ALL
            SELECT d + INTERVAL 1 DAY FROM date_axis WHERE d < :to
        )
        SELECT a.d AS statDate,
               COALESCE(s.click_count, 0)   AS clickCount,
               COALESCE(s.visitor_count, 0) AS visitorCount
        FROM date_axis a
        LEFT JOIN link_daily_stats s
               ON s.stat_date = a.d AND s.link_id = :linkId
        ORDER BY a.d
        """, nativeQuery = true)
    List<DailyStatRow> findDailyFilled(@Param("linkId") Long linkId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    @Query(value = """
        SELECT COALESCE(SUM(s.click_count), 0)      AS totalClicks,
               COALESCE(SUM(s.visitor_count), 0)     AS sumOfDailyVisitors,
               COALESCE(ROUND(AVG(s.click_count),1), 0) AS avgDailyClicks,
               COALESCE(MAX(s.click_count), 0)       AS peakClicks,
               COUNT(*)                              AS activeDays
        FROM link_daily_stats s
        WHERE s.link_id = :linkId AND s.stat_date BETWEEN :from AND :to
        """, nativeQuery = true)
    DailyStatSummaryRow findSummary(@Param("linkId") Long linkId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query(value = """
        SELECT l.id          AS linkId,
               l.link_id     AS linkUuid,
               l.slug        AS slug,
               l.title       AS title,
               COALESCE(SUM(s.click_count), 0)   AS totalClicks,
               COALESCE(SUM(s.visitor_count), 0) AS sumOfDailyVisitors,
               COALESCE(MAX(s.click_count), 0)   AS peakDailyClicks
        FROM links l
        LEFT JOIN link_daily_stats s
               ON s.link_id = l.id AND s.stat_date BETWEEN :from AND :to
        WHERE l.id IN (:linkIds)
        GROUP BY l.id, l.link_id, l.slug, l.title
        ORDER BY totalClicks DESC
        """, nativeQuery = true)
    List<LinkCompareRow> compareLinks(@Param("linkIds") List<Long> linkIds,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);


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

    interface DailyStatRow {
        LocalDate getStatDate();
        Integer getClickCount();
        Integer getVisitorCount();
    }

    interface DailyStatSummaryRow {
        Long getTotalClicks();
        Long getSumOfDailyVisitors();
        Double getAvgDailyClicks();
        Integer getPeakClicks();
        Long getActiveDays();
    }

    interface LinkCompareRow {
        Long getLinkId();
        String getLinkUuid();
        String getSlug();
        String getTitle();
        Long getTotalClicks();
        Long getSumOfDailyVisitors();
        Integer getPeakDailyClicks();
    }




    /** 특정 날짜 단건 조회 (일별 증감률 계산용) */
    Optional<LinkDailyStat> findByLinkIdAndStatDate(Long linkId, LocalDate statDate);
}

