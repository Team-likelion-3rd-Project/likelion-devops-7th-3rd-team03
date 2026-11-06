package com.example.management.stats.service;

import com.example.management.stats.domain.LinkDailyDimensionStat;
import com.example.management.stats.domain.LinkDailyStat;
import com.example.management.stats.dto.DailyChangeResponse;
import com.example.management.stats.dto.DimensionBreakdownResponse;
import com.example.management.stats.dto.StatsResponses.*;
import com.example.management.stats.repository.LinkDailyDimensionStatRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StatsService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // redirect-service RealtimeStatsRecorder와 공유하는 Redis key contract다.
    private static final String CLICKS_KEY_PATTERN = "stats:%s:link:%d:clicks";
    private static final String UNIQUE_VISITORS_KEY_PATTERN = "stats:%s:link:%d:uv";

    private final LinkRepository linkRepository;
    private final LinkDailyStatRepository dailyStatRepository;
    private final LinkDailyDimensionStatRepository dimensionStatRepository;

    private final StringRedisTemplate redisTemplate;

    public DailyStatsResponse getDailyStats(Long userId, String linkUuid, LocalDate from, LocalDate to) {
        Link link = resolveOwnedLink(userId, linkUuid);
        LocalDate today = LocalDate.now(KST);

        List<DailyStat> daily = dailyStatRepository.findDailyFilled(link.getId(), from, to).stream()
                .map(r -> toDailyStat(r, link.getId(), today))
                .toList();

        var s = dailyStatRepository.findSummary(link.getId(), from, to);
        DailySummary summary = new DailySummary(
                s.getTotalClicks(), s.getSumOfDailyVisitors(),
                s.getAvgDailyClicks() == null ? 0.0 : s.getAvgDailyClicks(),
                s.getPeakClicks(), s.getActiveDays());

        return new DailyStatsResponse(daily, summary);
    }

    public List<LinkCompare> compareLinks(Long userId, List<String> linkUuids, LocalDate from, LocalDate to) {
        if (linkUuids == null || linkUuids.isEmpty()) {
            throw new IllegalArgumentException("linkIds must not be empty");
        }

        List<Link> links = linkRepository.findByLinkIdInAndUserIdAndIsVisibleTrue(linkUuids, userId);
        List<Long> internalIds = links.stream().map(Link::getId).toList();

        if (internalIds.isEmpty()) {
            return List.of();
        }

        return dailyStatRepository.compareLinks(internalIds, from, to).stream()
                .map(r -> new LinkCompare(r.getLinkUuid(), r.getSlug(), r.getTitle(),
                        r.getTotalClicks(), r.getSumOfDailyVisitors(), r.getPeakDailyClicks()))
                .toList();
    }

    public List<ReferrerStat> getReferrerStats(Long userId, String linkUuid, LocalDate from, LocalDate to) {
        Link link = resolveOwnedLink(userId, linkUuid);

        return dimensionStatRepository.findReferrerStats(link.getId(), from, to).stream()
                .map(r -> new ReferrerStat(r.getReferrerCategory(), r.getClickCount(), r.getPercentage()))
                .toList();
    }

    // ===================================================================
    // 일별 증감률 / 기타 분포(디바이스·지역) / 실시간 접속자 수
    // ===================================================================

    /**
     * 일별 증감률 조회 (FR-004-2)
     * baseDate(기본값: 어제) vs 그 전날을 비교한다.
     * (당일 값은 배치 집계 전이라 이 테이블엔 없음 — 필요하면 realtime API로 별도 조회)
     */
    public DailyChangeResponse getDailyChange(Long userId, String linkUuid, LocalDate baseDate) {
        Link link = resolveOwnedLink(userId, linkUuid);
        LocalDate previousDate = baseDate.minusDays(1);

        Optional<LinkDailyStat> base = dailyStatRepository.findByLinkIdAndStatDate(link.getId(), baseDate);
        Optional<LinkDailyStat> previous = dailyStatRepository.findByLinkIdAndStatDate(link.getId(), previousDate);

        int baseClicks = base.map(LinkDailyStat::getClickCount).orElse(0);
        int prevClicks = previous.map(LinkDailyStat::getClickCount).orElse(0);
        int baseVisitors = base.map(LinkDailyStat::getVisitorCount).orElse(0);
        int prevVisitors = previous.map(LinkDailyStat::getVisitorCount).orElse(0);

        return new DailyChangeResponse(
                linkUuid,
                baseDate,
                DailyChangeResponse.MetricChange.of(baseClicks, prevClicks),
                DailyChangeResponse.MetricChange.of(baseVisitors, prevVisitors)
        );
    }

    /**
     * 기타 분포 조회 (FR-004-5 디바이스·지역)
     * dimensionType은 Controller에서 enum으로 미리 검증한 뒤 넘어온다
     * (잘못된 값이면 valueOf()에서 즉시 IllegalArgumentException 발생 → 조용히 0건 나오는 것 방지).
     */
    public DimensionBreakdownResponse getBreakdown(
            Long userId,
            String linkUuid,
            LinkDailyDimensionStat.DimensionType dimensionType,
            LocalDate from,
            LocalDate to
    ) {
        Link link = resolveOwnedLink(userId, linkUuid);

        var rows = dimensionStatRepository.findBreakdown(link.getId(), dimensionType.name(), from, to);
        long total = rows.stream().mapToLong(r -> r.getClickCount()).sum();

        List<DimensionBreakdownResponse.Item> items = rows.stream()
                .map(r -> new DimensionBreakdownResponse.Item(
                        r.getDimensionValue(),
                        r.getClickCount().intValue(),
                        total == 0 ? 0.0 : Math.round(r.getClickCount() * 1000.0 / total) / 10.0
                ))
                .toList();

        return new DimensionBreakdownResponse(linkUuid, dimensionType.name(), items);
    }

    /** 대시보드 실시간 endpoint가 소유권을 한 번만 확인하도록 click·UV를 함께 읽는다. */
    public RealtimeStats getRealtimeStats(Long userId, String linkUuid) {
        Link link = resolveOwnedLink(userId, linkUuid);
        LocalDate today = LocalDate.now(KST);
        String clicks = redisTemplate.opsForValue().get(clicksKey(today, link.getId()));
        HyperLogLogOperations<String, String> hyperLogLog = redisTemplate.opsForHyperLogLog();
        long uniqueVisitors = hyperLogLog.size(uniqueVisitorsKey(today, link.getId()));
        return new RealtimeStats(clicks == null ? 0L : Long.parseLong(clicks), uniqueVisitors);
    }

    public record RealtimeStats(long clickCount, long uniqueVisitorCount) {
    }

    private String clicksKey(LocalDate date, long linkId) {
        return CLICKS_KEY_PATTERN.formatted(date, linkId);
    }

    private String uniqueVisitorsKey(LocalDate date, long linkId) {
        return UNIQUE_VISITORS_KEY_PATTERN.formatted(date, linkId);
    }

    /**
     * 오늘 날짜는 Athena 배치가 아직 집계 전이라 findDailyFilled가 항상 0으로
     * 채워서 돌려준다. 그래서 오늘 하루만 Redis 실시간 값으로 대체한다
     * (getRealtimeStats()와 같은 Redis 키를 읽음).
     */
    private DailyStat toDailyStat(LinkDailyStatRepository.DailyStatRow row, long linkId, LocalDate today) {
        if (!row.getStatDate().equals(today)) {
            return new DailyStat(row.getStatDate(), row.getClickCount(), row.getVisitorCount());
        }
        String clicks = redisTemplate.opsForValue().get(clicksKey(today, linkId));
        long uniqueVisitors = redisTemplate.opsForHyperLogLog().size(uniqueVisitorsKey(today, linkId));
        return new DailyStat(today, clicks == null ? 0 : Integer.parseInt(clicks), (int) uniqueVisitors);
    }

    private Link resolveOwnedLink(Long userId, String linkUuid) {
        return linkRepository.findByLinkIdAndUserIdAndIsVisibleTrue(linkUuid, userId)
                .orElseThrow(() -> new IllegalArgumentException("link not found or not owned: " + linkUuid));
    }
}
