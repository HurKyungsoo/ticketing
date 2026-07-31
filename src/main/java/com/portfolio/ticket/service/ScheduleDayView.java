package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceSchedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 공연 상세의 "회차 선택"을 날짜 단위로 묶은 화면용 모델.
 *
 * <p>종전에는 회차를 그냥 한 줄로 나열했다. 로컬 시드처럼 3회차뿐이면 티가 안 나지만,
 * KOPIS 실데이터는 요일별로 여러 시각이 잡히는 공연이 많아 수십 개 칩이 한 덩어리로
 * 쏟아진다 — 며칠 중 어느 날인지, 그 날 몇 시 회차가 있는지 읽어낼 수가 없다.
 *
 * <p>{@link SeatMapView} 와 같은 이유로 템플릿이 아니라 여기서 묶는다. Thymeleaf 에서
 * 그룹핑하면 정렬 보장이 사라진다.
 */
public record ScheduleDayView(LocalDate date, List<PerformanceSchedule> schedules) {

    /**
     * 시간순으로 정렬된 회차 목록을 날짜별로 묶는다.
     *
     * <p><b>입력이 {@code showAt} 오름차순이어야 한다</b>
     * ({@code findByPerformanceIdOrderByShowAtAsc}). 정렬을 전제로 직전 묶음의 날짜만
     * 비교하므로, 순서가 섞여 들어오면 같은 날짜가 여러 묶음으로 쪼개진다.
     * Map 으로 모으지 않는 이유도 같다 — 입력 순서를 그대로 결과 순서로 쓰기 위함이다.
     */
    public static List<ScheduleDayView> groupByDay(List<PerformanceSchedule> schedules) {
        List<ScheduleDayView> days = new ArrayList<>();
        for (PerformanceSchedule schedule : schedules) {
            LocalDate date = schedule.getShowAt().toLocalDate();
            ScheduleDayView last = days.isEmpty() ? null : days.get(days.size() - 1);
            if (last == null || !last.date().equals(date)) {
                last = new ScheduleDayView(date, new ArrayList<>());
                days.add(last);
            }
            last.schedules().add(schedule);
        }
        return days;
    }

    /** 그 날 회차가 전부 매진인지. 날짜 줄 전체를 흐리게 표시하는 데 쓴다. */
    public boolean soldOut() {
        return schedules.stream().allMatch(s -> s.getRemainingSeats() == 0);
    }

    /** 그 날 남은 좌석 합계. */
    public int remainingSeats() {
        return schedules.stream().mapToInt(PerformanceSchedule::getRemainingSeats).sum();
    }
}
