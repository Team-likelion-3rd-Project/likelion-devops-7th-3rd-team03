package com.example.management.stats.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * link_daily_stats 테이블 (V1__init_schema.sql 기준)
 *
 * - (link_id, stat_date) UNIQUE + UPSERT로 중복 집계를 막는다.
 * - 배치가 단독 writer다. 확정된 과거일만 저장하고, 당일 수치는 조회 시점에
 *   Redis 카운터를 읽어 합산한다(그래서 이 테이블에는 오늘 행이 없다).
 * - 통계 API는 원본 로그(click_events)를 직접 조회하지 않고 이 테이블만 참조한다.
 * - click_events는 30일만 보관하므로, 30일이 지나면 이 테이블이 유일한 집계 소스다.
 *   재집계가 필요하면 30일 이내에만 가능하다.
 *
 * 유입경로/기기/지역 분포는 이 테이블이 아니라 LinkDailyDimensionStat이 담당한다.
 *
 * UPSERT 주의:
 *   JPA로 "조회 후 없으면 insert"를 하면 링크 수만큼 2N 쿼리가 나간다.
 *   배치에서는 네이티브 INSERT ... ON DUPLICATE KEY UPDATE 를 배치 실행할 것.
 *   updateCounts()는 단건 보정/재집계용이다.
 */
@Entity
@Table(
        name = "link_daily_stats",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_link_date", columnNames = {"link_id", "stat_date"})
        },
        indexes = {
                @Index(name = "idx_daily_stats_link_date", columnList = "link_id, stat_date"),
                // 기간 조회 시 PK 재조회 없이 인덱스만으로 결과를 만드는 커버링 인덱스
                @Index(name = "idx_daily_stats_covering",
                        columnList = "link_id, stat_date, click_count, visitor_count")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkDailyStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 집계 대상 링크 (links.id). DB에 FK가 있으나 JPA 연관관계로는 매핑하지 않는다 */
    @Column(name = "link_id", nullable = false, updatable = false)
    private Long linkId;

    /** 집계 기준일 (KST). clicked_at은 UTC이므로 배치에서 변환해 넣는다 */
    @Column(name = "stat_date", nullable = false, updatable = false)
    private LocalDate statDate;

    /** 봇 트래픽(is_bot = true)을 제외한 클릭 수 */
    @Column(name = "click_count", nullable = false)
    private int clickCount;

    /**
     * 하루 안에서만 유효한 순 방문자 수.
     * 여러 날의 값을 SUM하면 같은 방문자가 날짜 수만큼 중복 계산된다.
     * 기간 UV가 필요하면 click_events에서 COUNT(DISTINCT visitor_id)로 직접 계산할 것
     * (보관 기간 30일 이내에서만 가능. visitor_id는 V4에서 visitor_hash를 대체한
     * 방문자 식별 쿠키 UUID 컬럼).
     */
    @Column(name = "visitor_count", nullable = false)
    private int visitorCount;

    @Builder
    private LinkDailyStat(Long linkId, LocalDate statDate, int clickCount, int visitorCount) {
        this.linkId = linkId;
        this.statDate = statDate;
        this.clickCount = requireNonNegative(clickCount, "clickCount");
        this.visitorCount = requireNonNegative(visitorCount, "visitorCount");
    }

    /** 단건 보정/재집계용. 대량 배치는 네이티브 UPSERT를 사용할 것 */
    public void updateCounts(int clickCount, int visitorCount) {
        this.clickCount = requireNonNegative(clickCount, "clickCount");
        this.visitorCount = requireNonNegative(visitorCount, "visitorCount");
    }

    /** UV는 클릭 수를 넘을 수 없다. 집계 로직 검증용 */
    public boolean isConsistent() {
        return visitorCount <= clickCount;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative: " + value);
        }
        return value;
    }
}
