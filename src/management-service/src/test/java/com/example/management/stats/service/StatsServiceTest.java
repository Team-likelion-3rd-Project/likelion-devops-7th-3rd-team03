package com.example.management.stats.service;

import com.example.management.links.domain.Link;
import com.example.management.links.repository.LinkRepository;
import com.example.management.stats.domain.LinkDailyDimensionStat;
import com.example.management.stats.domain.LinkDailyStat;
import com.example.management.stats.dto.DailyChangeResponse;
import com.example.management.stats.dto.DimensionBreakdownResponse;
import com.example.management.stats.repository.LinkDailyDimensionStatRepository;
import com.example.management.stats.repository.LinkDailyStatRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long TEST_USER_ID = 1L;
    private static final String LINK_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Long INTERNAL_LINK_ID = 10L;

    @Mock
    private LinkRepository linkRepository;
    @Mock
    private LinkDailyStatRepository dailyStatRepository;
    @Mock
    private LinkDailyDimensionStatRepository dimensionStatRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HyperLogLogOperations<String, String> hyperLogLogOperations;

    @InjectMocks
    private StatsService statsService;

    private void mockOwnedLink() {
        Link link = Mockito.mock(Link.class);
        when(link.getId()).thenReturn(INTERNAL_LINK_ID);
        when(linkRepository.findByLinkIdAndUserIdAndIsVisibleTrue(LINK_UUID, TEST_USER_ID))
                .thenReturn(Optional.of(link));
    }

    @Test
    void 일별_증감률은_현재_사용자가_소유한_링크의_두_날짜를_비교한다() {
        mockOwnedLink();
        LocalDate baseDate = LocalDate.of(2026, 8, 6);
        when(dailyStatRepository.findByLinkIdAndStatDate(INTERNAL_LINK_ID, baseDate))
                .thenReturn(Optional.of(stat(baseDate, 120, 95)));
        when(dailyStatRepository.findByLinkIdAndStatDate(INTERNAL_LINK_ID, baseDate.minusDays(1)))
                .thenReturn(Optional.of(stat(baseDate.minusDays(1), 100, 80)));

        DailyChangeResponse response = statsService.getDailyChange(TEST_USER_ID, LINK_UUID, baseDate);

        assertThat(response.clicks().changeRate()).isEqualTo(20.0);
        assertThat(response.visitors().changeRate()).isEqualTo(18.8);
    }

    @Test
    void 분포_조회는_현재_사용자가_소유한_링크만_조회한다() {
        mockOwnedLink();
        LocalDate date = LocalDate.of(2026, 8, 6);
        when(dimensionStatRepository.findBreakdown(INTERNAL_LINK_ID, "DEVICE", date, date))
                .thenReturn(List.of(breakdownRow("MOBILE", 70L), breakdownRow("DESKTOP", 30L)));

        DimensionBreakdownResponse response = statsService.getBreakdown(
                TEST_USER_ID, LINK_UUID, LinkDailyDimensionStat.DimensionType.DEVICE, date, date
        );

        assertThat(response.breakdown()).hasSize(2);
        assertThat(response.breakdown().get(0).percentage()).isEqualTo(70.0);
    }

    @Nested
    class Realtime {

        @Test
        void 클릭과_UV를_KST_기준_공유_Redis_키에서_함께_읽는다() {
            mockOwnedLink();
            LocalDate today = LocalDate.now(KST);
            String clicksKey = "stats:%s:link:%d:clicks".formatted(today, INTERNAL_LINK_ID);
            String uvKey = "stats:%s:link:%d:uv".formatted(today, INTERNAL_LINK_ID);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(clicksKey)).thenReturn("42");
            when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
            when(hyperLogLogOperations.size(uvKey)).thenReturn(37L);

            StatsService.RealtimeStats result = statsService.getRealtimeStats(TEST_USER_ID, LINK_UUID);

            assertThat(result.clickCount()).isEqualTo(42L);
            assertThat(result.uniqueVisitorCount()).isEqualTo(37L);
        }

        @Test
        void Redis_값이_없으면_클릭과_UV는_0이다() {
            mockOwnedLink();
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
            when(hyperLogLogOperations.size(org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);

            StatsService.RealtimeStats result = statsService.getRealtimeStats(TEST_USER_ID, LINK_UUID);

            assertThat(result.clickCount()).isZero();
            assertThat(result.uniqueVisitorCount()).isZero();
        }

        @Test
        void 소유하지_않은_링크는_Redis_조회_전에_거부한다() {
            when(linkRepository.findByLinkIdAndUserIdAndIsVisibleTrue(anyString(), eq(TEST_USER_ID)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> statsService.getRealtimeStats(TEST_USER_ID, "not-owned"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class DailyStats {

        @Test
        void 오늘_날짜는_배치_대신_Redis_실시간_값으로_대체한다() {
            mockOwnedLink();
            LocalDate yesterday = LocalDate.now(KST).minusDays(1);
            LocalDate today = LocalDate.now(KST);

            when(dailyStatRepository.findDailyFilled(INTERNAL_LINK_ID, yesterday, today))
                    .thenReturn(List.of(
                            dailyStatRow(yesterday, 10, 5),
                            dailyStatRow(today, 0, 0) // findDailyFilled는 배치 전이라 오늘을 항상 0으로 채워 준다
                    ));
            when(dailyStatRepository.findSummary(INTERNAL_LINK_ID, yesterday, today))
                    .thenReturn(summaryRow(10L, 5L, 10.0, 10, 1L));

            String todayClicksKey = "stats:%s:link:%d:clicks".formatted(today, INTERNAL_LINK_ID);
            String todayUvKey = "stats:%s:link:%d:uv".formatted(today, INTERNAL_LINK_ID);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(todayClicksKey)).thenReturn("7");
            when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
            when(hyperLogLogOperations.size(todayUvKey)).thenReturn(3L);

            var response = statsService.getDailyStats(TEST_USER_ID, LINK_UUID, yesterday, today);

            assertThat(response.daily()).hasSize(2);
            assertThat(response.daily().get(0).clickCount()).isEqualTo(10); // 어제는 배치 값 그대로
            assertThat(response.daily().get(1).date()).isEqualTo(today);
            assertThat(response.daily().get(1).clickCount()).isEqualTo(7); // 오늘은 Redis 값으로 대체됨
            assertThat(response.daily().get(1).visitorCount()).isEqualTo(3);
        }

        @Test
        void 오늘_Redis에_값이_없으면_0이다() {
            mockOwnedLink();
            LocalDate today = LocalDate.now(KST);
            String todayClicksKey = "stats:%s:link:%d:clicks".formatted(today, INTERNAL_LINK_ID);
            String todayUvKey = "stats:%s:link:%d:uv".formatted(today, INTERNAL_LINK_ID);

            when(dailyStatRepository.findDailyFilled(INTERNAL_LINK_ID, today, today))
                    .thenReturn(List.of(dailyStatRow(today, 0, 0)));
            when(dailyStatRepository.findSummary(INTERNAL_LINK_ID, today, today))
                    .thenReturn(summaryRow(0L, 0L, 0.0, 0, 0L));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(todayClicksKey)).thenReturn(null); // 해당 키에 값이 없는 상황을 명시적으로 재현
            when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
            when(hyperLogLogOperations.size(todayUvKey)).thenReturn(0L);

            var response = statsService.getDailyStats(TEST_USER_ID, LINK_UUID, today, today);

            assertThat(response.daily().get(0).clickCount()).isZero();
        }
    }

    private LinkDailyStat stat(LocalDate date, int clicks, int visitors) {
        return LinkDailyStat.builder()
                .linkId(INTERNAL_LINK_ID)
                .statDate(date)
                .clickCount(clicks)
                .visitorCount(visitors)
                .build();
    }

    private LinkDailyStatRepository.DailyStatRow dailyStatRow(LocalDate date, int clicks, int visitors) {
        return new LinkDailyStatRepository.DailyStatRow() {
            @Override
            public LocalDate getStatDate() {
                return date;
            }

            @Override
            public Integer getClickCount() {
                return clicks;
            }

            @Override
            public Integer getVisitorCount() {
                return visitors;
            }
        };
    }

    private LinkDailyStatRepository.DailyStatSummaryRow summaryRow(
            Long totalClicks, Long sumOfDailyVisitors, Double avgDailyClicks, Integer peakClicks, Long activeDays) {
        return new LinkDailyStatRepository.DailyStatSummaryRow() {
            @Override
            public Long getTotalClicks() {
                return totalClicks;
            }

            @Override
            public Long getSumOfDailyVisitors() {
                return sumOfDailyVisitors;
            }

            @Override
            public Double getAvgDailyClicks() {
                return avgDailyClicks;
            }

            @Override
            public Integer getPeakClicks() {
                return peakClicks;
            }

            @Override
            public Long getActiveDays() {
                return activeDays;
            }
        };
    }

    private LinkDailyDimensionStatRepository.BreakdownRow breakdownRow(String value, Long clicks) {
        return new LinkDailyDimensionStatRepository.BreakdownRow() {
            @Override
            public String getDimensionValue() {
                return value;
            }

            @Override
            public Long getClickCount() {
                return clicks;
            }
        };
    }
}
