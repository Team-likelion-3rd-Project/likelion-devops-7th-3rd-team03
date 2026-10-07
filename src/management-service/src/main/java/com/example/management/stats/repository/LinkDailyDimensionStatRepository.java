package com.example.management.stats.repository;

import com.example.management.stats.domain.LinkDailyDimensionStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LinkDailyDimensionStatRepository extends JpaRepository<LinkDailyDimensionStat, Long> {

    @Query(value = """
        SELECT d.dimension_value AS referrerCategory,
               SUM(d.click_count) AS clickCount,
               ROUND(100.0 * SUM(d.click_count) / NULLIF(SUM(SUM(d.click_count)) OVER (), 0), 1) AS percentage
        FROM link_daily_dimension_stats d
        WHERE d.link_id = :linkId
          AND d.dimension_type = 'REFERRER'
          AND d.stat_date BETWEEN :from AND :to
        GROUP BY d.dimension_value
        ORDER BY clickCount DESC
        """, nativeQuery = true)
    List<ReferrerStatRow> findReferrerStats(@Param("linkId") Long linkId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    interface ReferrerStatRow {
        String getReferrerCategory();
        Long getClickCount();
        Double getPercentage();
    }


    @Query(value = """
    SELECT d.dimension_value AS dimensionValue, SUM(d.click_count) AS clickCount
    FROM link_daily_dimension_stats d
    WHERE d.link_id = :linkId AND d.dimension_type = :dimensionType
      AND d.stat_date BETWEEN :from AND :to
    GROUP BY d.dimension_value
    ORDER BY clickCount DESC
    """, nativeQuery = true)
    List<BreakdownRow> findBreakdown(@Param("linkId") Long linkId,
                                     @Param("dimensionType") String dimensionType,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    interface BreakdownRow {
        String getDimensionValue();
        Long getClickCount();
    }

}
