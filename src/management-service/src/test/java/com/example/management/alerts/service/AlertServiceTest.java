package com.example.management.alerts.service;

import com.example.management.alerts.dto.AlertResponses.*;
import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.service.WorkspaceAccess;
import com.example.management.workspace.service.WorkspaceAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 기준 시각: 2026-11-04 12:00 KST (03:00 UTC). 최근 7일 = 10/28 ~ 11/03 */
@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final String WS = "ws";
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 4, 3, 0);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 10, 28);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 11, 3);

    @Mock private WorkspaceAccessChecker accessChecker;
    @Mock private LinkRepository linkRepository;
    @Mock private LinkDailyStatRepository dailyRepo;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    private AlertService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), AlertService.KST);
        service = new AlertService(accessChecker, linkRepository, dailyRepo, redis, "https://s.short.ly", 10, clock);
        when(workspace.getId()).thenReturn(10L);
        when(accessChecker.requireMember(WS, 99L)).thenReturn(new WorkspaceAccess(workspace, member));
        lenient().when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void 만료_임박은_정확히_7일_뒤까지_포함하고_3일_이하는_ACTION() {
        Link in3days = link(1, NOW_UTC.plusDays(3));
        Link exactly7 = link(2, NOW_UTC.plusDays(7));
        Link over7 = link(3, NOW_UTC.plusDays(7).plusSeconds(1));
        givenLinks(in3days, exactly7, over7);
        givenToday("0", "0", "0");
        givenWeek();

        List<Alert> alerts = service.getAlerts(99L, WS).alerts();

        assertThat(alerts).extracting(a -> a.link().linkId()).containsExactly("l1", "l2");
        assertThat(alerts).extracting(Alert::level).containsExactly("ACTION", "WATCH");
        assertThat(((ExpiringDetail) alerts.get(0).detail()).daysLeft()).isEqualTo(3);
        assertThat(alerts.get(0).link().shortUrl()).isEqualTo("https://s.short.ly/s1");
    }

    @Test
    void 이미_만료된_링크는_대상이_아니다() {
        givenLinks(link(1, NOW_UTC.minusMinutes(1)));

        assertThat(service.getAlerts(99L, WS).alerts()).isEmpty();
        verifyNoInteractions(values, dailyRepo);
    }

    @Test
    void 방문자_급증은_정확히_3배를_포함하고_평균_10_미만은_제외한다() {
        Link exactly3x = link(1, null);    // 평균 10, 오늘 30
        Link below3x = link(2, null);      // 평균 10, 오늘 29
        Link smallAvg = link(3, null);     // 평균 5, 오늘 100
        givenLinks(exactly3x, below3x, smallAvg);
        givenToday("30", "29", "100");
        givenWeek(sum(1, 70), sum(2, 70), sum(3, 35));

        List<Alert> alerts = service.getAlerts(99L, WS).alerts();

        assertThat(alerts).hasSize(1);
        SpikeDetail detail = (SpikeDetail) alerts.get(0).detail();
        assertThat(alerts.get(0).type()).isEqualTo("TRAFFIC_SPIKE");
        assertThat(detail.avgDailyClicks()).isEqualTo(10.0);
        assertThat(detail.ratio()).isEqualTo(3.0);
    }

    @Test
    void 두_조건에_다_해당하면_두_건이고_ACTION_만료임박_급증_순으로_정렬한다() {
        Link both = link(1, NOW_UTC.plusDays(5));      // WATCH 만료 + 급증
        Link urgent = link(2, NOW_UTC.plusDays(1));    // ACTION 만료
        Link bigSpike = link(3, null);                 // 급증 비율이 더 큼
        givenLinks(both, urgent, bigSpike);
        givenToday("40", "0", "100");
        givenWeek(sum(1, 70), sum(3, 70));

        List<Alert> alerts = service.getAlerts(99L, WS).alerts();

        assertThat(alerts).extracting(a -> a.type() + ":" + a.link().linkId()).containsExactly(
                "EXPIRING_SOON:l2",   // ACTION
                "EXPIRING_SOON:l1",   // WATCH 만료 임박
                "TRAFFIC_SPIKE:l3",   // 비율 10.0
                "TRAFFIC_SPIKE:l1");  // 비율 4.0
    }

    @Test
    void Redis가_응답하지_않으면_급증만_빼고_만료_임박은_응답한다() {
        givenLinks(link(1, NOW_UTC.plusDays(2)));
        when(values.multiGet(anyList())).thenThrow(new QueryTimeoutException("redis down"));

        List<Alert> alerts = service.getAlerts(99L, WS).alerts();

        assertThat(alerts).extracting(Alert::type).containsExactly("EXPIRING_SOON");
        verifyNoInteractions(dailyRepo);
    }

    private void givenLinks(Link... links) {
        when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(10L)).thenReturn(List.of(links));
    }

    private void givenToday(String... clicks) {
        when(values.multiGet(anyList())).thenReturn(List.of(clicks));
    }

    private void givenWeek(LinkDailyStatRepository.LinkClickSumRow... rows) {
        when(dailyRepo.sumClicksByLink(anyCollection(), eq(WEEK_FROM), eq(WEEK_TO))).thenReturn(List.of(rows));
    }

    private Link link(long id, LocalDateTime expiresAt) {
        Link link = mock(Link.class);
        lenient().when(link.getId()).thenReturn(id);
        lenient().when(link.getLinkId()).thenReturn("l" + id);
        lenient().when(link.getTitle()).thenReturn("title" + id);
        lenient().when(link.getSlug()).thenReturn("s" + id);
        lenient().when(link.getExpiresAt()).thenReturn(expiresAt);
        return link;
    }

    private static LinkDailyStatRepository.LinkClickSumRow sum(long linkId, long clicks) {
        return new LinkDailyStatRepository.LinkClickSumRow() {
            public Long getLinkId() { return linkId; }
            public Long getClickCount() { return clicks; }
        };
    }
}