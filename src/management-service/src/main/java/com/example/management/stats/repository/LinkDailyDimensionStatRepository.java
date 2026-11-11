package com.example.management.stats.repository;

import com.example.management.stats.domain.LinkDailyDimensionStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface LinkDailyDimensionStatRepository extends JpaRepository<LinkDailyDimensionStat, Long> {

    @Query(value = """
        SELECT d.link_id AS linkId, d.dimension_value AS dimensionValue, SUM(d.click_count) AS clickCount
        FROM link_daily_dimension_stats d
        WHERE d.link_id IN (:linkIds) AND d.dimension_type = :dimensionType
          AND d.stat_date BETWEEN :from AND :to
        GROUP BY d.link_id, d.dimension_value
        """, nativeQuery = true)
    List<LinkBreakdownRow> findBreakdownRows(@Param("linkIds") Collection<Long> linkIds,
                                             @Param("dimensionType") String dimensionType,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    interface LinkBreakdownRow {
        Long getLinkId();
        String getDimensionValue();
        Long getClickCount();
    }

}
