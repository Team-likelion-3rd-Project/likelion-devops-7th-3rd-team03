package com.example.management.stats.service;

import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import com.example.management.stats.domain.LinkDailyDimensionStat.DimensionType;
import com.example.management.stats.exception.StatsLinkNotFoundException;
import com.example.management.stats.repository.LinkDailyDimensionStatRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import com.example.management.workspace.domain.Workspace;
import com.example.management.workspace.domain.WorkspaceMember;
import com.example.management.workspace.service.WorkspaceAccess;
import com.example.management.workspace.service.WorkspaceAccessChecker;
import org.springframework.dao.QueryTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * strict stubs (MockitoExtension 기본): 조건이 안 맞는 호출이나 안 쓰인 stub 이 있으면 실패한다.
 * 모든 테스트가 쓰지 않는 공통 stub 은 lenient() 로 표시한다.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceStatsServiceTest {

    private static final String WS = "ws-uuid";
    private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 2);

    @Mock private WorkspaceAccessChecker accessChecker;
    @Mock private LinkRepository linkRepository;
    @Mock private LinkDailyStatRepository dailyRepo;
    @Mock private LinkDailyDimensionStatRepository dimRepo;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private HyperLogLogOperations<String, String> hll;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;
    @Mock private Link linkA;
    @Mock
    private Link linkB;

    private WorkspaceStatsService service;

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        service = new WorkspaceStatsService(accessChecker, linkRepository, dailyRepo, dimRepo, redis);

        // 모든 테스트가 거치는 권한 확인
        when(workspace.getId()).thenReturn(10L);
        when(accessChecker.requireMember(WS, 99L)).thenReturn(new WorkspaceAccess(workspace, member));

        // 테스트마다 쓰는 것이 달라 lenient
        lenient().when(linkA.getId()).thenReturn(1L);
        lenient().when(linkA.getLinkId()).thenReturn("a");
        lenient().when(linkB.getId()).thenReturn(2L);
        lenient().when(linkB.getLinkId()).thenReturn("b");
        lenient().when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(10L)).thenReturn(List.of(linkA, linkB));
        lenient().when(redis.opsForValue()).thenReturn(values);
        lenient().when(redis.opsForHyperLogLog()).thenReturn(hll);
    }

    @Test
    void 대상이_없으면_팀_전체를_한_묶음으로_합산한다() {
        when(dailyRepo.findDailyRows(any(), eq(D1), eq(D2))).thenReturn(List.of(
                daily(1L, D1, 10, 5), daily(2L, D1, 3, 2), daily(1L, D2, 4, 4)));

        var groups = service.getDaily(99L, WS, null, null, D1, D2).groups();

        assertThat(groups).hasSize(1);
        var group = groups.get(0);
        assertThat(group.linkId()).isNull();
        assertThat(group.daily()).extracting("clickCount").containsExactly(13L, 4L);
        assertThat(group.daily()).extracting("visitorCount").containsExactly(7L, 4L);   // 링크별 방문자의 합
        assertThat(group.summary().totalClicks()).isEqualTo(17);
        assertThat(group.summary().peakClicks()).isEqualTo(13);
        assertThat(group.summary().activeDays()).isEqualTo(2);
        assertThat(group.summary().avgDailyClicks()).isEqualTo(8.5);
    }

    @Test
    void linkIds면_요청한_순서대로_링크마다_묶음_하나() {
        when(linkRepository.findByLinkIdInAndWorkspaceIdAndIsVisibleTrue(anyCollection(), eq(10L)))
                .thenReturn(List.of(linkA, linkB));
        when(dailyRepo.findDailyRows(any(), eq(D1), eq(D1))).thenReturn(List.of(daily(1L, D1, 10, 5), daily(2L, D1, 3, 2)));

        var groups = service.getDaily(99L, WS, List.of("b", "a", "b"), null, D1, D1).groups();

        assertThat(groups).extracting("linkId").containsExactly("b", "a");   // 중복 제거, 순서 유지
        assertThat(groups.get(0).summary().totalClicks()).isEqualTo(3);
    }

    @Test
    void 분포는_클릭을_합한_뒤_비율을_다시_계산한다() {
        when(dimRepo.findBreakdownRows(any(), eq("DEVICE"), eq(D1), eq(D2))).thenReturn(List.of(
                breakdown(1L, "MOBILE", 30), breakdown(2L, "MOBILE", 10), breakdown(2L, "DESKTOP", 60)));

        var group = service.getBreakdown(99L, WS, null, null, DimensionType.DEVICE, D1, D2).groups().get(0);

        assertThat(group.breakdown()).extracting("value").containsExactly("DESKTOP", "MOBILE");
        assertThat(group.breakdown()).extracting("clickCount").containsExactly(60L, 40L);
        assertThat(group.breakdown()).extracting("percentage").containsExactly(60.0, 40.0);
    }

    @Test
    void 실시간_방문자는_여러_키를_합쳐_센다() {
        when(values.multiGet(anyList())).thenReturn(List.of("7", "3"));
        when(hll.size(any(String[].class))).thenReturn(8L);

        var group = service.getRealtime(99L, WS, null, null).groups().get(0);

        assertThat(group.clickCount()).isEqualTo(10);
        assertThat(group.uniqueVisitorCount()).isEqualTo(8);   // 7 + 3 이 아니라 합집합
    }

    @Test
    void 팀에_없는_링크가_하나라도_있으면_전체를_거절한다() {
        when(linkRepository.findByLinkIdInAndWorkspaceIdAndIsVisibleTrue(anyCollection(), eq(10L)))
                .thenReturn(List.of(linkA));

        assertThatThrownBy(() -> service.getDaily(99L, WS, List.of("a", "x"), null, D1, D1))
                .isInstanceOfSatisfying(StatsLinkNotFoundException.class,
                        e -> assertThat(e.getLinkIds()).containsExactly("x"));
    }

    @Test
    void 기간과_대상_검증() {
        assertThatThrownBy(() -> service.getDaily(99L, WS, null, null, D2, D1))
                .isInstanceOf(IllegalArgumentException.class);                                   // from > to
        assertThatThrownBy(() -> service.getDaily(99L, WS, null, null, D1, D1.plusDays(90)))
                .isInstanceOf(IllegalArgumentException.class);                                   // 90일 초과
        assertThatThrownBy(() -> service.getDaily(99L, WS, List.of("a"), List.of("p"), D1, D1))
                .isInstanceOf(IllegalArgumentException.class);                                   // 함께 보냄
        List<String> many = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "l" + i).toList();
        assertThatThrownBy(() -> service.getDaily(99L, WS, many, null, D1, D1))
                .isInstanceOf(IllegalArgumentException.class);                                   // 21개
    }

    @Test
    void 링크가_없는_팀은_0으로_채운다() {
        when(linkRepository.findByWorkspaceIdAndIsVisibleTrue(10L)).thenReturn(List.of());

        var group = service.getDaily(99L, WS, null, null, D1, D2).groups().get(0);

        assertThat(group.daily()).extracting("clickCount").containsExactly(0L, 0L);
        verifyNoInteractions(dailyRepo);   // 빈 IN 절 쿼리를 보내지 않는다
    }

    @Test
    void 오늘_값은_링크_수와_상관없이_Redis_왕복_두_번으로_읽는다() {
        when(dailyRepo.findDailyRows(any(), eq(TODAY), eq(TODAY))).thenReturn(List.of());
        when(values.multiGet(anyList())).thenReturn(List.of("7", "3"));
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(5L, 2L));

        var group = service.getDaily(99L, WS, null, null, TODAY, TODAY).groups().get(0);

        assertThat(group.daily()).extracting("clickCount").containsExactly(10L);
        assertThat(group.daily()).extracting("visitorCount").containsExactly(7L);
        verify(values, times(1)).multiGet(anyList());                     // 링크마다 GET 하지 않는다
        verify(redis, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    void Redis가_응답하지_않아도_일별_통계는_오늘을_0으로_응답한다() {
        when(dailyRepo.findDailyRows(any(), eq(TODAY.minusDays(1)), eq(TODAY)))
                .thenReturn(List.of(daily(1L, TODAY.minusDays(1), 4, 3)));
        when(values.multiGet(anyList())).thenThrow(new QueryTimeoutException("redis down"));

        var group = service.getDaily(99L, WS, null, null, TODAY.minusDays(1), TODAY).groups().get(0);

        assertThat(group.daily()).extracting("clickCount").containsExactly(4L, 0L);
    }

    private static Link link(long id, String uuid) {
        Link link = mock(Link.class);
        when(link.getId()).thenReturn(id);
        when(link.getLinkId()).thenReturn(uuid);
        return link;
    }

    private static LinkDailyStatRepository.LinkDailyRow daily(long linkId, LocalDate date, int clicks, int visitors) {
        return new LinkDailyStatRepository.LinkDailyRow() {
            public Long getLinkId() { return linkId; }
            public LocalDate getStatDate() { return date; }
            public Integer getClickCount() { return clicks; }
            public Integer getVisitorCount() { return visitors; }
        };
    }

    private static LinkDailyDimensionStatRepository.LinkBreakdownRow breakdown(long linkId, String value, long clicks) {
        return new LinkDailyDimensionStatRepository.LinkBreakdownRow() {
            public Long getLinkId() { return linkId; }
            public String getDimensionValue() { return value; }
            public Long getClickCount() { return clicks; }
        };
    }
}