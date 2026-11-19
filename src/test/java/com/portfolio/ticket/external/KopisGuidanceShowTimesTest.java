package com.portfolio.ticket.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KOPIS 공연 시각 원문(dtguidance)의 요일이 실제 공연 날짜와 안 맞는 경우(2026-11-18 전체 점검 발견 3).
 * 운영 상영 중 KOPIS 2,431건 중 4건 — 모두 하루 공연이었다.
 */
class KopisGuidanceShowTimesTest {

    private static final LocalDate SUN_DEC_13 = LocalDate.of(2026, 12, 13);

    @DisplayName("주를 넘어가는 요일 범위도 읽는다 — 「목요일 ~ 수요일」은 한 바퀴 전부")
    @Test
    void wrapAroundRange() {
        assertThat(KopisGuidanceParser.showTimes("목요일 ~ 수요일(19:30)"))
                .containsOnlyKeys(EnumSet.allOf(DayOfWeek.class))
                .allSatisfy((day, times) -> assertThat(times).containsExactly(LocalTime.of(19, 30)));
        assertThat(KopisGuidanceParser.showTimes("토요일 ~ 월요일(14:00)"))
                .containsOnlyKeys(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY);
    }

    @DisplayName("원문 요일이 기간에 하나도 없으면 원문 시각을 기간의 모든 날에 쓴다 — 공연 371")
    @Test
    void weekdayMissingFromPeriod() {
        Map<DayOfWeek, List<LocalTime>> fitted = KopisGuidanceParser.fitToPeriod(
                KopisGuidanceParser.showTimes("토요일(15:00)"), SUN_DEC_13, SUN_DEC_13);

        assertThat(fitted.get(DayOfWeek.SUNDAY)).containsExactly(LocalTime.of(15, 0));
    }

    @DisplayName("한 날짜에 여러 회차도 그대로 옮긴다 — 공연 8064")
    @Test
    void multipleTimes() {
        LocalDate sunday = LocalDate.of(2027, 1, 10);
        Map<DayOfWeek, List<LocalTime>> fitted = KopisGuidanceParser.fitToPeriod(
                KopisGuidanceParser.showTimes("토요일(13:00,15:00,17:00)"), sunday, sunday);

        assertThat(fitted.get(DayOfWeek.SUNDAY))
                .containsExactly(LocalTime.of(13, 0), LocalTime.of(15, 0), LocalTime.of(17, 0));
    }

    @DisplayName("기간 안에 원문 요일이 하나라도 있으면 원문 그대로 둔다")
    @Test
    void untouchedWhenAnyDayMatches() {
        Map<DayOfWeek, List<LocalTime>> raw = KopisGuidanceParser.showTimes("토요일(15:00)");
        // 12/12(토) ~ 12/13(일)
        assertThat(KopisGuidanceParser.fitToPeriod(raw, SUN_DEC_13.minusDays(1), SUN_DEC_13)).isSameAs(raw);
    }

    @DisplayName("요일마다 시각이 다르면 어느 쪽인지 모르니 손대지 않는다")
    @Test
    void untouchedWhenTimesDiffer() {
        Map<DayOfWeek, List<LocalTime>> raw = KopisGuidanceParser.showTimes("금요일(19:30), 토요일(15:00)");
        assertThat(KopisGuidanceParser.fitToPeriod(raw, SUN_DEC_13, SUN_DEC_13)).isSameAs(raw);
    }

    @DisplayName("원문이 없으면 null 그대로")
    @Test
    void nullStaysNull() {
        assertThat(KopisGuidanceParser.fitToPeriod(null, SUN_DEC_13, SUN_DEC_13)).isNull();
    }
}
