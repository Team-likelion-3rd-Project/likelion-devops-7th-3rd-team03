package com.example.management.batch;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AthenaBatchRunnerTargetDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    @Test
    void 인자가_없으면_어제() {
        assertThat(AthenaBatchRunner.resolveTargetDate(new String[]{}, TODAY)).isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    void today면_오늘() {
        assertThat(AthenaBatchRunner.resolveTargetDate(new String[]{"today"}, TODAY)).isEqualTo(TODAY);
        assertThat(AthenaBatchRunner.resolveTargetDate(new String[]{"TODAY"}, TODAY)).isEqualTo(TODAY);
    }

    @Test
    void 날짜면_그_날짜() {
        assertThat(AthenaBatchRunner.resolveTargetDate(new String[]{"2026-10-01"}, TODAY)).isEqualTo(LocalDate.of(2026, 10, 1));
    }
}