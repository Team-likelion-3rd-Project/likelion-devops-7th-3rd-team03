package com.example.management.stats.service;

import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import com.example.management.stats.domain.LinkDailyDimensionStat.DimensionType;
import com.example.management.stats.dto.WorkspaceStatsResponses.*;
import com.example.management.stats.exception.StatsLinkNotFoundException;
import com.example.management.stats.repository.LinkDailyDimensionStatRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import com.example.management.workspace.service.WorkspaceAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 워크스페이스 통계 (#208). 처리 순서: 팀 멤버 확인 → 대상을 묶음별 링크 목록으로 변환 → 묶음별 계산.
 *
 * 쿼리는 묶음 수와 상관없이 한 번이고, 묶음별 합산은 여기서 한다.
 * 여러 링크를 합산하는 묶음(팀 전체)의 규칙
 *   - 클릭은 더한다
 *   - 방문자(visitorCount, sumOfDailyVisitors)는 링크별 방문자의 합이라 여러 링크를 방문한 사람을 중복해서 센다
 *   - 분포의 percentage 는 클릭을 먼저 합한 뒤 다시 계산한다
 *   - 실시간 uniqueVisitorCount 는 링크별 HyperLogLog 를 합쳐서(PFCOUNT 여러 키) 근사해 중복이 없다
 * 오늘(KST)이 기간에 들어가면 daily 의 오늘 값은 Redis 실시간 값으로 채운다. 분포는 배치 집계만 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceStatsService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_TARGETS = 20;
    private static final long MAX_RANGE_DAYS = 90;
    // redirect 의 RealtimeStatsRecorder, 기존 StatsService 와 같은 키
    private static final String CLICKS_KEY = "stats:%s:link:%d:clicks";
    private static final String UV_KEY = "stats:%s:link:%d:uv";

    private final WorkspaceAccessChecker accessChecker;
    private final LinkRepository linkRepository;
    private final LinkDailyStatRepository dailyStatRepository;
    private final LinkDailyDimensionStatRepository dimensionStatRepository;
    private final StringRedisTemplate redisTemplate;

    /** 묶음 하나. linkId·projectId 가 둘 다 null 이면 팀 전체 */
    record Group(String linkId, String projectId, List<Long> linkIds) {
    }

    public DailyResponse getDaily(Long userId, String workspaceId, List<String> linkIds, List<String> projectIds,
                                  LocalDate from, LocalDate to) {
        List<Group> groups = resolveGroups(userId, workspaceId, linkIds, projectIds);
        validateRange(from, to);

        Set<Long> allLinks = allLinkIds(groups);
        Map<Long, Map<LocalDate, long[]>> stored = new HashMap<>();
        if (!allLinks.isEmpty()) {
            for (var row : dailyStatRepository.findDailyRows(allLinks, from, to)) {
                stored.computeIfAbsent(row.getLinkId(), k -> new HashMap<>())
                        .put(row.getStatDate(), new long[]{row.getClickCount(), row.getVisitorCount()});
            }
        }

        LocalDate today = LocalDate.now(KST);
        boolean includesToday = !today.isBefore(from) && !today.isAfter(to);
        Map<Long, long[]> todayByLink = includesToday ? readToday(allLinks, today) : Map.of();

        List<DailyGroup> result = groups.stream().map(group -> {
            List<DailyPoint> daily = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                long clicks = 0;
                long visitors = 0;
                for (Long linkId : group.linkIds()) {
                    long[] value = d.equals(today) && includesToday
                            ? todayByLink.getOrDefault(linkId, new long[]{0, 0})
                            : stored.getOrDefault(linkId, Map.of()).getOrDefault(d, new long[]{0, 0});
                    clicks += value[0];
                    visitors += value[1];
                }
                daily.add(new DailyPoint(d, clicks, visitors));
            }
            return new DailyGroup(group.linkId(), group.projectId(), daily, summarize(daily));
        }).toList();

        return new DailyResponse(result);
    }

    public BreakdownResponse getBreakdown(Long userId, String workspaceId, List<String> linkIds,
                                          List<String> projectIds, DimensionType type,
                                          LocalDate from, LocalDate to) {
        List<Group> groups = resolveGroups(userId, workspaceId, linkIds, projectIds);
        validateRange(from, to);

        Set<Long> allLinks = allLinkIds(groups);
        Map<Long, Map<String, Long>> byLink = new HashMap<>();
        if (!allLinks.isEmpty()) {
            for (var row : dimensionStatRepository.findBreakdownRows(allLinks, type.name(), from, to)) {
                byLink.computeIfAbsent(row.getLinkId(), k -> new HashMap<>())
                        .merge(row.getDimensionValue(), row.getClickCount(), Long::sum);
            }
        }

        List<BreakdownGroup> result = groups.stream().map(group -> {
            // 클릭을 먼저 합하고 비율은 합한 값으로 다시 계산한다
            Map<String, Long> merged = new HashMap<>();
            group.linkIds().forEach(id -> byLink.getOrDefault(id, Map.of()).forEach((k, v) -> merged.merge(k, v, Long::sum)));
            long total = merged.values().stream().mapToLong(Long::longValue).sum();
            List<BreakdownItem> items = merged.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .map(e -> new BreakdownItem(e.getKey(), e.getValue(), percentage(e.getValue(), total)))
                    .toList();
            return new BreakdownGroup(group.linkId(), group.projectId(), items);
        }).toList();

        return new BreakdownResponse(type.name(), result);
    }

    public RealtimeResponse getRealtime(Long userId, String workspaceId, List<String> linkIds, List<String> projectIds) {
        List<Group> groups = resolveGroups(userId, workspaceId, linkIds, projectIds);
        LocalDate today = LocalDate.now(KST);

        List<RealtimeGroup> result = groups.stream().map(group -> {
            if (group.linkIds().isEmpty()) {
                return new RealtimeGroup(group.linkId(), group.projectId(), 0, 0);
            }
            List<String> clickKeys = group.linkIds().stream().map(id -> CLICKS_KEY.formatted(today, id)).toList();
            long clicks = Optional.ofNullable(redisTemplate.opsForValue().multiGet(clickKeys)).orElse(List.of())
                    .stream().filter(Objects::nonNull).mapToLong(Long::parseLong).sum();
            String[] uvKeys = group.linkIds().stream().map(id -> UV_KEY.formatted(today, id)).toArray(String[]::new);
            // PFCOUNT 여러 키 = HyperLogLog 합집합 근사. 여러 링크를 방문한 사람을 한 번만 센다
            Long visitors = redisTemplate.opsForHyperLogLog().size(uvKeys);
            return new RealtimeGroup(group.linkId(), group.projectId(), clicks, visitors == null ? 0 : visitors);
        }).toList();

        return new RealtimeResponse(result);
    }

    private List<Group> resolveGroups(Long userId, String workspaceId, List<String> linkIds, List<String> projectIds) {
        Long wsId = accessChecker.requireMember(workspaceId, userId).workspace().getId();

        boolean hasLinks = linkIds != null && !linkIds.isEmpty();
        boolean hasProjects = projectIds != null && !projectIds.isEmpty();
        if (hasLinks && hasProjects) {
            throw new IllegalArgumentException("linkIds and projectIds together");
        }
        if (hasProjects) {
            // 프로젝트 기능 구현 때 추가한다 (명세)
            throw new IllegalArgumentException("projectIds not supported yet");
        }
        if (!hasLinks) {
            List<Long> ids = linkRepository.findByWorkspaceIdAndIsVisibleTrue(wsId).stream().map(Link::getId).toList();
            return List.of(new Group(null, null, ids));
        }

        // 같은 ID 는 한 번만, 요청한 순서대로
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(linkIds));
        if (distinct.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("too many linkIds");
        }
        Map<String, Link> found = linkRepository.findByLinkIdInAndWorkspaceIdAndIsVisibleTrue(distinct, wsId).stream()
                .collect(Collectors.toMap(Link::getLinkId, Function.identity()));
        List<String> missing = distinct.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new StatsLinkNotFoundException(missing);
        }
        return distinct.stream().map(id -> new Group(id, null, List.of(found.get(id).getId()))).toList();
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("invalid date range");
        }
    }

    private static Set<Long> allLinkIds(List<Group> groups) {
        return groups.stream().flatMap(g -> g.linkIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<Long, long[]> readToday(Collection<Long> linkIds, LocalDate today) {
        Map<Long, long[]> result = new HashMap<>();
        for (Long id : linkIds) {
            String clicks = redisTemplate.opsForValue().get(CLICKS_KEY.formatted(today, id));
            Long visitors = redisTemplate.opsForHyperLogLog().size(UV_KEY.formatted(today, id));
            result.put(id, new long[]{clicks == null ? 0 : Long.parseLong(clicks), visitors == null ? 0 : visitors});
        }
        return result;
    }

    private static Summary summarize(List<DailyPoint> daily) {
        long total = daily.stream().mapToLong(DailyPoint::clickCount).sum();
        long visitors = daily.stream().mapToLong(DailyPoint::visitorCount).sum();
        long peak = daily.stream().mapToLong(DailyPoint::clickCount).max().orElse(0);
        long activeDays = daily.stream().filter(p -> p.clickCount() > 0).count();
        double avg = activeDays == 0 ? 0 : Math.round(total * 10.0 / activeDays) / 10.0;
        return new Summary(total, visitors, avg, peak, activeDays);
    }

    private static double percentage(long value, long total) {
        return total == 0 ? 0.0 : Math.round(value * 1000.0 / total) / 10.0;
    }
}