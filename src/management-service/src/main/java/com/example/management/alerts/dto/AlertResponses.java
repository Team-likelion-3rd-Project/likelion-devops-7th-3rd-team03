package com.example.management.alerts.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 팀 알림 응답 (#209). 알림은 저장하지 않고 조회할 때 계산한다 */
public final class AlertResponses {

    private AlertResponses() {
    }

    public record AlertsResponse(List<Alert> alerts) {}

    public record Alert(String type, String level, AlertLink link, Object detail) {}

    public record AlertLink(String linkId, String title, String shortUrl) {}

    /** EXPIRING_SOON: 만료 시각(UTC)과 남은 일수(KST 날짜 기준, 0 = 오늘 만료) */
    public record ExpiringDetail(OffsetDateTime expiresAt, long daysLeft) {}

    /** TRAFFIC_SPIKE: 오늘 클릭(Redis), 최근 7일 일평균, 오늘 / 평균 (소수 한 자리) */
    public record SpikeDetail(long todayClicks, double avgDailyClicks, double ratio) {}
}