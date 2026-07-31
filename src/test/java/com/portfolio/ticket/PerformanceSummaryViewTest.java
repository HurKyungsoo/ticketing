package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.dto.GradePriceRow;
import com.portfolio.ticket.service.PerformanceSummaryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공연 상세 왼쪽 요약(최저가 · 가장 빠른 회차).
 *
 * <p>화면으로는 잘 안 드러나는 경계가 둘 있다 — <b>회차가 전부 지난 공연</b>과
 * <b>가격을 알 수 없는 공연</b>. 둘 다 실데이터에 존재하는데(기간이 이미 시작된 공연,
 * 회차가 아직 없는 수집 직후) 화면에서는 그냥 카드가 조금 달라 보일 뿐이라 놓치기 쉽다.
 */
class PerformanceSummaryViewTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    private Performance performance(Integer basePrice) {
        return Performance.builder().title("테스트 공연").basePrice(basePrice).build();
    }

    private PerformanceSchedule scheduleAt(LocalDateTime showAt) {
        return PerformanceSchedule.builder()
                .showAt(showAt).totalSeats(100).remainingSeats(100).build();
    }

    private GradePriceRow gradePrice(String grade, int price) {
        GradePriceRow row = new GradePriceRow();
        row.setGrade(grade);
        row.setPrice(price);
        return row;
    }

    @DisplayName("최저가는 등급별 가격 중 가장 싼 값")
    @Test
    void lowestPriceIsCheapestGrade() {
        var view = PerformanceSummaryView.of(
                performance(999_999),
                List.of(),
                List.of(gradePrice("VIP", 180_000), gradePrice("A", 96_000), gradePrice("R", 144_000)),
                NOW);

        assertThat(view.lowestPrice()).isEqualTo(96_000);
    }

    @DisplayName("등급별 가격이 없으면 basePrice 로 대체 (회차가 아직 없는 수집 직후)")
    @Test
    void fallsBackToBasePriceWhenNoGrades() {
        var view = PerformanceSummaryView.of(performance(50_000), List.of(), List.of(), NOW);
        assertThat(view.lowestPrice()).isEqualTo(50_000);
    }

    @DisplayName("등급별 가격도 basePrice 도 없으면 null — 화면에서 최저가 줄을 숨긴다")
    @Test
    void lowestPriceIsNullWhenNothingKnown() {
        var view = PerformanceSummaryView.of(performance(null), List.of(), List.of(), NOW);
        assertThat(view.lowestPrice()).isNull();
    }

    /**
     * 첫 회차를 그냥 쓰면 안 되는 이유. 기간이 이미 시작된 공연은 앞쪽 회차가 지나 있어서
     * "가장 빠른 회차"라며 어제 날짜를 보여주게 된다.
     */
    @DisplayName("가장 빠른 회차는 아직 안 지난 것 중 첫 번째 — 지난 회차는 건너뛴다")
    @Test
    void nextScheduleSkipsPastOnes() {
        var view = PerformanceSummaryView.of(
                performance(50_000),
                List.of(
                        scheduleAt(NOW.minusDays(2)),
                        scheduleAt(NOW.minusHours(1)),
                        scheduleAt(NOW.plusHours(3)),     // 이게 답
                        scheduleAt(NOW.plusDays(5))),
                List.of(),
                NOW);

        assertThat(view.nextSchedule()).isNotNull();
        assertThat(view.nextSchedule().getShowAt()).isEqualTo(NOW.plusHours(3));
    }

    @DisplayName("회차가 전부 지났으면 null")
    @Test
    void nextScheduleIsNullWhenAllPast() {
        var view = PerformanceSummaryView.of(
                performance(50_000),
                List.of(scheduleAt(NOW.minusDays(3)), scheduleAt(NOW.minusDays(1))),
                List.of(),
                NOW);

        assertThat(view.nextSchedule()).isNull();
    }

    @DisplayName("보여줄 게 하나도 없으면 hasAnything() 이 false — 카드째 숨긴다")
    @Test
    void hasAnythingIsFalseWhenEmpty() {
        var empty = PerformanceSummaryView.of(
                performance(null), List.of(scheduleAt(NOW.minusDays(1))), List.of(), NOW);
        assertThat(empty.hasAnything()).isFalse();

        var priceOnly = PerformanceSummaryView.of(performance(30_000), List.of(), List.of(), NOW);
        assertThat(priceOnly.hasAnything()).isTrue();
    }
}
