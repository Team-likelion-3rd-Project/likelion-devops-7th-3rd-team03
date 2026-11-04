package com.example.management.alerts.service;

import com.example.management.alerts.dto.AlertResponses.*;
import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import com.example.management.workspace.service.WorkspaceAccessChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 팀 알림 (#209). 저장하지 않고 조회할 때 링크 정보와 통계로 계산한다.
 *   - 만료 임박(EXPIRING_SOON): 지금부터 7일(168시간) 안에 만료. 정확히 7일 뒤는 포함. 남은 일수 3 이하면 ACTION, 아니면 WATCH
 *   - 방문자 급증(TRAFFIC_SPIKE, WATCH): 오늘 클릭 >= 최근 7일(어제까지, KST) 일평균 × 3. 평균이 최소 기준 미만이면 제외
 * 대상은 팀의 삭제되지 않았고 아직 만료되지 않은 링크다. 통계는 IN 쿼리 한 번, 오늘 클릭은 Redis MGET 한 번으로 읽는다.
 * 트랜잭션을 걸지 않는다 (Redis 를 읽는 동안 DB 커넥션을 붙잡지 않게).
 */
@Slf4j
@Service
public class AlertService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration EXPIRING_WINDOW = Duration.ofDays(7);
    private static final long ACTION_DAYS = 3;
    private static final int SPIKE_MULTIPLIER = 3;
    private static final int AVG_DAYS = 7;
    private static final String CLICKS_KEY = "stats:%s:link:%d:clicks";   // RealtimeStatsRecorder 와 같은 키

    private final WorkspaceAccessChecker accessChecker;
    private final LinkRepository linkRepository;
    private final LinkDailyStatRepository dailyStatRepository;
    private final StringRedisTemplate redisTemplate;
    private final String shortUrlBase;
    private final double spikeMinAvgDailyClicks;
    private final Clock clock;

    @Autowired
    public AlertService(WorkspaceAccessChecker accessChecker,
                        LinkRepository linkRepository,
                        LinkDailyStatRepository dailyStatRepository,
                        StringRedisTemplate redisTemplate,
                        @Value("${short-url.base-url}") String shortUrlBase,
                        @Value("${app.alerts.spike-min-avg-daily-clicks:10}") double spikeMinAvgDailyClicks) {
        this(accessChecker, linkRepository, dailyStatRepository, redisTemplate,
                shortUrlBase, spikeMinAvgDailyClicks, Clock.system(KST));
    }

    /** 테스트용: 시각을 고정한다 */
    AlertService(WorkspaceAccessChecker accessChecker, LinkRepository linkRepository,
                 LinkDailyStatRepository dailyStatRepository, StringRedisTemplate redisTemplate,
                 String shortUrlBase, double spikeMinAvgDailyClicks, Clock clock) {
        this.accessChecker = accessChecker;
        this.linkRepository = linkRepository;
        this.dailyStatRepository = dailyStatRepository;
        this.redisTemplate = redisTemplate;
        this.shortUrlBase = shortUrlBase.endsWith("/") ? shortUrlBase.substring(0, shortUrlBase.length() - 1) : shortUrlBase;
        this.spikeMinAvgDailyClicks = spikeMinAvgDailyClicks;
        this.clock = clock;
    }

    public AlertsResponse getAlerts(Long userId, String workspaceId) {
        Long wsId = accessChecker.requireMember(workspaceId, userId).workspace().getId();

        // links.expires_at 은 UTC 로 저장된다 (DB 접속 serverTimezone=UTC)
        LocalDateTime nowUtc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<Link> links = linkRepository.findByWorkspaceIdAndIsVisibleTrue(wsId).stream()
                .filter(link -> link.getExpiresAt() == null || link.getExpiresAt().isAfter(nowUtc))
                .toList();

        List<Alert> alerts = new ArrayList<>();
        links.forEach(link -> expiring(link, nowUtc).ifPresent(alerts::add));
        alerts.addAll(spikes(links));
        alerts.sort(ORDER);
        return new AlertsResponse(alerts);
    }

    private Optional<Alert> expiring(Link link, LocalDateTime nowUtc) {
        LocalDateTime expiresAt = link.getExpiresAt();
        if (expiresAt == null || expiresAt.isAfter(nowUtc.plus(EXPIRING_WINDOW))) {
            return Optional.empty();   // 정확히 7일 뒤는 포함한다
        }
        LocalDate todayKst = LocalDate.now(clock.withZone(KST));
        LocalDate expiresKst = expiresAt.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
        long daysLeft = ChronoUnit.DAYS.between(todayKst, expiresKst);
        String level = daysLeft <= ACTION_DAYS ? "ACTION" : "WATCH";
        return Optional.of(new Alert("EXPIRING_SOON", level, toLink(link),
                new ExpiringDetail(expiresAt.atOffset(ZoneOffset.UTC), daysLeft)));
    }

    private List<Alert> spikes(List<Link> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        LocalDate todayKst = LocalDate.now(clock.withZone(KST));
        List<Long> ids = links.stream().map(Link::getId).toList();

        Map<Long, Long> todayClicks;
        try {
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(ids.stream().map(id -> CLICKS_KEY.formatted(todayKst, id)).toList());
            todayClicks = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                String v = values == null ? null : values.get(i);
                todayClicks.put(ids.get(i), v == null ? 0L : Long.parseLong(v));
            }
        } catch (DataAccessException e) {
            // 오늘 클릭을 모르면 급증을 판단할 수 없다. 만료 임박 알림은 그대로 응답한다
            log.warn("Redis 에서 오늘 클릭을 읽지 못해 방문자 급증 알림을 건너뜁니다. links={}", ids.size(), e);
            return List.of();
        }

        Map<Long, Long> weekSums = dailyStatRepository
                .sumClicksByLink(ids, todayKst.minusDays(AVG_DAYS), todayKst.minusDays(1)).stream()
                .collect(Collectors.toMap(LinkDailyStatRepository.LinkClickSumRow::getLinkId,
                        LinkDailyStatRepository.LinkClickSumRow::getClickCount));

        List<Alert> result = new ArrayList<>();
        for (Link link : links) {
            double avg = weekSums.getOrDefault(link.getId(), 0L) / (double) AVG_DAYS;
            long today = todayClicks.getOrDefault(link.getId(), 0L);
            if (avg < spikeMinAvgDailyClicks || today < avg * SPIKE_MULTIPLIER) {
                continue;   // 평균이 너무 작으면 3배가 의미 없다. 정확히 3배는 포함한다
            }
            result.add(new Alert("TRAFFIC_SPIKE", "WATCH", toLink(link),
                    new SpikeDetail(today, round1(avg), round1(today / avg))));
        }
        return result;
    }

    private AlertLink toLink(Link link) {
        return new AlertLink(link.getLinkId(), link.getTitle(), shortUrlBase + "/" + link.getSlug());
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** ACTION 먼저. 같은 단계 안에서는 만료 임박(만료 빠른 순) → 방문자 급증(비율 큰 순) */
    private static final Comparator<Alert> ORDER = Comparator
            .comparingInt((Alert a) -> "ACTION".equals(a.level()) ? 0 : 1)
            .thenComparingInt(a -> "EXPIRING_SOON".equals(a.type()) ? 0 : 1)
            .thenComparing(a -> a.detail() instanceof ExpiringDetail d ? d.expiresAt().toEpochSecond() : 0L)
            .thenComparing(a -> a.detail() instanceof SpikeDetail d ? -d.ratio() : 0.0);
}