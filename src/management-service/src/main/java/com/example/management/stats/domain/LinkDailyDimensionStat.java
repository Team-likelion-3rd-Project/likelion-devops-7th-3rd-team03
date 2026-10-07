package com.example.management.stats.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * link_daily_dimension_stats 테이블 (V1__init_schema.sql 기준)
 *
 * 유입경로(FR-004-4), 기기·지역 분포(FR-004-5) 리포트용 집계 테이블.
 *
 * 이 테이블이 필요한 이유:
 *   click_events는 30일만 보관하고 통계 API는 원본 로그를 직접 조회하지 않는다.
 *   이 집계가 없으면 30일이 지난 시점의 분포는 낼 방법이 사라지고, 나중에 만들어도
 *   그 시점 기준 최근 30일치밖에 채울 수 없다. 그래서 오픈 전 필수다.
 *
 * 차원마다 테이블을 따로 만들지 않고 (dimension_type, dimension_value) 한 쌍으로 처리한다.
 * 새 차원(BROWSER, OS 등)이 생겨도 스키마 변경 없이 enum 상수만 추가하면 된다.
 *
 * 행 수 제어:
 *   REGION은 값 종류가 많아 행이 불어난다. 배치에서 링크·일자별 상위 N개만 저장하고
 *   나머지는 ETC 한 행으로 합칠 것. 그렇지 않으면 링크 1개의 하루치가 수백 행이 된다.
 *
 * UV를 집계하지 않는 이유:
 *   차원별 COUNT(DISTINCT visitor_hash)는 비용이 크고, FR-004-4/5가 요구하는 것은
 *   "비율"이라 클릭 수만으로 충분하다.
 */
@Entity
@Table(
        name = "link_daily_dimension_stats",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_link_daily_dimension",
                        columnNames = {"link_id", "stat_date", "dimension_type", "dimension_value"})
        },
        indexes = {
                @Index(name = "idx_dim_stats_link_date_type",
                        columnList = "link_id, stat_date, dimension_type")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkDailyDimensionStat {

    /** 상위 N개 밖의 값들을 합산해 담는 버킷 */
    public static final String ETC = "ETC";

    /** 수집 시점에 값을 얻지 못한 경우 (UA 파싱 실패, IP 매핑 실패 등) */
    public static final String UNKNOWN = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 집계 대상 링크의 내부 식별자 (links.id, BIGINT). DB에 FK가 있으나 JPA 연관관계로는 매핑하지 않는다.
     * 주의: 같은 이름의 필드가 Link.linkId(String, 외부 노출용 UUID, links.link_id 컬럼)로도 존재한다.
     * 둘은 이름만 같을 뿐 타입도 가리키는 컬럼도 다르다 — API 경로에서 받은 UUID를 그대로 여기 넣지 말 것.
     * 리포지토리/서비스 계층에서 반드시 UUID -> 내부 id 변환을 거친 뒤 넘겨야 한다.
     */
    @Column(name = "link_id", nullable = false, updatable = false)
    private Long linkId;

    /** 집계 기준일 (KST) */
    @Column(name = "stat_date", nullable = false, updatable = false)
    private LocalDate statDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_type", length = 20, nullable = false, updatable = false)
    private DimensionType dimensionType;

    /**
     * 차원 값. click_events의 대응 컬럼을 그대로 쓴다.
     *   REFERRER -> referrer_category (INSTAGRAM, DIRECT, ETC ...)
     *   DEVICE   -> device_type       (MOBILE, DESKTOP, TABLET)
     *   REGION   -> region            (Seoul, Busan ...)
     * 원본이 null인 행은 UNKNOWN으로 치환해 저장한다(컬럼은 NOT NULL).
     */
    @Column(name = "dimension_value", length = 100, nullable = false, updatable = false)
    private String dimensionValue;

    /**
     * 봇 트래픽(is_bot = true)을 제외한 클릭 수.
     * link_daily_stats.clickCount(int)와 동일 타입으로 통일했다 — 차원별 클릭 수는
     * 같은 링크/같은 날짜의 총 클릭 수(link_daily_stats.clickCount)를 절대 넘을 수 없어
     * long(BIGINT)일 근거가 없었다.
     */
    @Column(name = "click_count", nullable = false)
    private int clickCount;

    @Builder
    private LinkDailyDimensionStat(Long linkId, LocalDate statDate, DimensionType dimensionType,
                                   String dimensionValue, int clickCount) {
        this.linkId = linkId;
        this.statDate = statDate;
        this.dimensionType = dimensionType;
        this.dimensionValue = (dimensionValue == null || dimensionValue.isBlank())
                ? UNKNOWN : dimensionValue;
        this.clickCount = requireNonNegative(clickCount);
    }

    /** 단건 보정/재집계용. 대량 배치는 네이티브 UPSERT를 사용할 것 */
    public void updateClickCount(int clickCount) {
        this.clickCount = requireNonNegative(clickCount);
    }

    private static int requireNonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("clickCount must not be negative: " + value);
        }
        return value;
    }

    public enum DimensionType {
        REFERRER,
        DEVICE,
        REGION
    }
}
