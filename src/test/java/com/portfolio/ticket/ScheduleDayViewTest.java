package com.portfolio.ticket;

import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.service.ScheduleDayView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공연 상세의 회차를 날짜별로 묶는 로직.
 *
 * <p>로컬 시드는 하루에 한 회차뿐이라 화면으로는 이 동작을 확인할 수 없다. 정작 이 기능이
 * 필요한 건 KOPIS 실데이터처럼 <b>하루에 회차가 여러 개</b>인 공연이므로 여기서 덮는다.
 */
class ScheduleDayViewTest {

    private PerformanceSchedule scheduleAt(LocalDateTime showAt, int remaining) {
        return PerformanceSchedule.builder()
                .showAt(showAt)
                .totalSeats(100)
                .remainingSeats(remaining)
                .build();
    }

    @DisplayName("같은 날 회차는 한 묶음으로, 날짜 순서는 입력 순서 그대로")
    @Test
    void groupsSameDayTogether() {
        LocalDate day1 = LocalDate.of(2026, 8, 7);
        LocalDate day2 = LocalDate.of(2026, 8, 8);

        List<ScheduleDayView> days = ScheduleDayView.groupByDay(List.of(
                scheduleAt(day1.atTime(14, 0), 10),
                scheduleAt(day1.atTime(19, 0), 20),
                scheduleAt(day2.atTime(15, 0), 30)
        ));

        assertThat(days).hasSize(2);
        assertThat(days.get(0).date()).isEqualTo(day1);
        assertThat(days.get(0).schedules()).hasSize(2);
        assertThat(days.get(1).date()).isEqualTo(day2);
        assertThat(days.get(1).schedules()).hasSize(1);
    }

    @DisplayName("하루 전 회차가 매진이면 soldOut, 하나라도 남으면 아니다")
    @Test
    void soldOutOnlyWhenEveryShowIsSoldOut() {
        LocalDate day = LocalDate.of(2026, 8, 7);

        List<ScheduleDayView> allSold = ScheduleDayView.groupByDay(List.of(
                scheduleAt(day.atTime(14, 0), 0),
                scheduleAt(day.atTime(19, 0), 0)
        ));
        assertThat(allSold.get(0).soldOut()).isTrue();
        assertThat(allSold.get(0).remainingSeats()).isZero();

        List<ScheduleDayView> partial = ScheduleDayView.groupByDay(List.of(
                scheduleAt(day.atTime(14, 0), 0),
                scheduleAt(day.atTime(19, 0), 5)
        ));
        assertThat(partial.get(0).soldOut()).isFalse();
        assertThat(partial.get(0).remainingSeats()).isEqualTo(5);
    }

    @DisplayName("회차가 없으면 빈 목록")
    @Test
    void emptyInputGivesEmptyResult() {
        assertThat(ScheduleDayView.groupByDay(List.of())).isEmpty();
    }

    /**
     * 직전 묶음의 날짜만 비교하므로 정렬이 전제다. 그 전제를 코드에 적어만 두지 않고
     * 실제로 어떻게 깨지는지 고정해 둔다 — 나중에 정렬 없는 조회로 바꾸면 같은 날짜가
     * 두 묶음으로 쪼개져 화면에 8/7 이 두 번 나온다.
     */
    @DisplayName("입력이 시간순이 아니면 같은 날짜가 쪼개진다 (정렬 전제 문서화)")
    @Test
    void unsortedInputSplitsSameDay() {
        LocalDate day1 = LocalDate.of(2026, 8, 7);
        LocalDate day2 = LocalDate.of(2026, 8, 8);

        List<ScheduleDayView> days = ScheduleDayView.groupByDay(List.of(
                scheduleAt(day1.atTime(14, 0), 10),
                scheduleAt(day2.atTime(15, 0), 30),
                scheduleAt(day1.atTime(19, 0), 20)   // 다시 8/7 — 정렬돼 있었다면 없을 순서
        ));

        assertThat(days).hasSize(3);
        assertThat(days).extracting(ScheduleDayView::date)
                .containsExactly(day1, day2, day1);
    }
}
