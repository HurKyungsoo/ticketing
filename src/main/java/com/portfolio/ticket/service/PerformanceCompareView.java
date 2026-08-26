package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.dto.GradePriceRow;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 비교표 한 칸.
 *
 * <p>표기를 여기서 전부 끝내서 넘긴다 — 템플릿이 {@code T(PerformanceCategory).valueOf(...)}
 * 같은 걸 부르게 두면 화면마다 같은 값을 다른 방법으로 만들게 되고, 실제로 홈·목록 카드가
 * 그렇게 갈라진 적이 있다.
 *
 * <p>날짜·회차·예매 가능 여부는 전부 {@link PerformanceListRow} 의 static 헬퍼를 그대로
 * 쓴다. 비교표는 목록에서 고른 공연을 나란히 놓는 화면이라, 같은 공연이 목록에서는
 * 「내일 19:30」인데 비교표에서는 「8/27(목) 19:30」로 적히면 그 자체로 결함이다.
 */
public record PerformanceCompareView(
        Long id,
        String title,
        String posterUrl,
        String categoryLabel,
        String venue,
        String address,
        String runPeriod,
        PerformanceListRow.NextShow nextShow,
        Integer lowestPrice,
        PerformanceListRow.Availability availability,
        String runningTime,
        String ageLimit,
        int upcomingCount
) {

    /**
     * @param schedules   그 공연의 전체 회차. 지난 회차 걸러내기는 안에서 한다
     *                    ({@link PerformanceSummaryView} 와 같은 판단을 쓰기 위해서다).
     * @param gradePrices 등급별 가격. 최저가 계산은 상세 요약 카드와 같은 규칙을 재사용한다.
     * @param now         "지난 회차" 판정 기준. 표기(오늘·내일)는 이 값의 날짜를 쓴다.
     */
    public static PerformanceCompareView of(Performance performance,
                                             List<PerformanceSchedule> schedules,
                                             List<GradePriceRow> gradePrices,
                                             LocalDateTime now) {
        LocalDate today = now.toLocalDate();

        // 최저가·가장 빠른 회차는 상세의 요약 카드가 이미 답을 갖고 있다. 여기서 다시
        // 짜면 "얼마부터"가 두 화면에서 갈릴 수 있다.
        PerformanceSummaryView summary =
                PerformanceSummaryView.of(performance, schedules, gradePrices, now);
        PerformanceSchedule next = summary.nextSchedule();

        LocalDateTime nextShowAt = next != null ? next.getShowAt() : null;
        Integer nextRemaining = next != null ? next.getRemainingSeats() : null;

        return new PerformanceCompareView(
                performance.getId(),
                performance.getTitle(),
                performance.getPosterUrl(),
                performance.getCategory() != null ? performance.getCategory().label() : null,
                performance.getVenue(),
                performance.getAddress(),
                PerformanceListRow.periodFor(performance.getStartDate(), performance.getEndDate(), today),
                PerformanceListRow.nextShowFor(nextShowAt, today),
                summary.lowestPrice(),
                PerformanceListRow.availabilityFor(nextShowAt, nextRemaining, performance.getStartDate(), today),
                performance.getRunningTime(),
                performance.getAgeLimit(),
                (int) schedules.stream().filter(s -> !s.isPast(now)).count());
    }

    /** 예매하러 갈 수 있는 상태인지. 남은 회차가 하나도 없으면 CTA 를 죽인다. */
    public boolean bookable() {
        return upcomingCount > 0;
    }
}
