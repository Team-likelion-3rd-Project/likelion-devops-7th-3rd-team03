package com.example.management.batch;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 배치 재시도 대기 시간 계산 (exponential backoff + equal jitter).
 *
 * 기준 대기 = min(상한, 기본값 × 2^(시도-1))
 * 실제 대기 = 기준의 절반(고정) + [0, 기준의 절반] 무작위
 *
 * 절반을 고정으로 두는 이유: 완전 무작위(0~기준)면 운이 나쁘면 거의 안 기다리고 바로 재시도해서
 * 장애 상황을 더 악화시킬 수 있다. 최소 대기 시간을 보장하면서 재시도 시점만 흩어지게 한다.
 *
 * 사용처: DuckDB 쿼리 재시도, 일별/차원별 통계 upsert 재시도
 */
final class Backoff {

    private Backoff() {
    }

    static long equalJitter(long baseMillis, long capMillis, int attempt) {
        if (baseMillis <= 0) {
            return 0;
        }
        int exponent = Math.min(Math.max(attempt, 1) - 1, 20);  // 오버플로 방지
        long exponential = Math.min(capMillis, baseMillis * (1L << exponent));
        long half = exponential / 2;
        return half + ThreadLocalRandom.current().nextLong(exponential - half + 1);
    }
}