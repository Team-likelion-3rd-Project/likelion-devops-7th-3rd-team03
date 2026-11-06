package com.example.management.batch;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    @RepeatedTest(1000)
    void 대기_시간은_기준의_절반에서_기준_사이다() {
        // 기본 100ms → 1회 100, 2회 200, 3회 400
        assertThat(Backoff.equalJitter(100, 30_000, 1)).isBetween(50L, 100L);
        assertThat(Backoff.equalJitter(100, 30_000, 2)).isBetween(100L, 200L);
        assertThat(Backoff.equalJitter(100, 30_000, 3)).isBetween(200L, 400L);
    }

    @RepeatedTest(1000)
    void 상한을_넘지_않는다() {
        assertThat(Backoff.equalJitter(100, 1_000, 10)).isBetween(500L, 1_000L);
    }

    @Test
    void 매번_같은_값이_나오지_않는다() {
        Set<Long> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            values.add(Backoff.equalJitter(1_000, 30_000, 3));
        }
        // jitter가 없으면 항상 4000 하나만 나온다
        assertThat(values).hasSizeGreaterThan(10);
    }

    @Test
    void 기본값이_0이면_대기하지_않는다() {
        assertThat(Backoff.equalJitter(0, 30_000, 5)).isZero();
    }

    @Test
    void 시도_횟수가_매우_커도_오버플로_없이_상한에서_멈춘다() {
        assertThat(Backoff.equalJitter(2_000, 30_000, 100)).isBetween(15_000L, 30_000L);
    }
}