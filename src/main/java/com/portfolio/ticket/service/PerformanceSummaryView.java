package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.dto.GradePriceRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공연 상세 왼쪽(포스터 아래)에 붙는 요약.
 *
 * <p>포스터 칸은 sticky 라 스크롤 내내 화면에 남는데, 포스터(320x427) 아래가 끝까지 비어 있었다.
 * 긴 공연 소개를 읽다 보면 가격도 예매 진입점도 화면 밖으로 사라진다. 그 자리에 "얼마부터인지"와
 * "가장 빠른 회차"를 놓아 스크롤 위치와 무관하게 결정에 필요한 정보가 따라오게 한다.
 *
 * <p>둘 다 없을 수 있다(회차가 아직 없는 수집 직후, 이미 다 지난 공연). 그때는 화면에서
 * 카드째 숨긴다 — 빈 카드는 빈 공간보다 나쁘다.
 */
public record PerformanceSummaryView(Integer lowestPrice, PerformanceSchedule nextSchedule) {

    /**
     * @param gradePrices 실제 좌석에 매겨진 등급별 가격. 비어 있으면 basePrice 로 대체한다.
     * @param schedules   {@code showAt} 오름차순이어야 한다({@code findByPerformanceIdOrderByShowAtAsc}).
     * @param now         "지난 회차" 판정 기준. 테스트에서 고정하려고 파라미터로 받는다.
     */
    public static PerformanceSummaryView of(Performance performance,
                                             List<PerformanceSchedule> schedules,
                                             List<GradePriceRow> gradePrices,
                                             LocalDateTime now) {
        return new PerformanceSummaryView(
                lowestPriceOf(performance, gradePrices),
                nextScheduleOf(schedules, now));
    }

    /**
     * 등급 중 가장 싼 값. 상세의 관람료 줄은 등급을 전부 보여주지만, 요약에서는 "얼마부터"만
     * 필요하다. 좌석에 매겨진 실제 가격을 우선하고, 회차가 없어 좌석도 없으면 수집 원본의
     * basePrice 로 대체한다(둘 다 없으면 null → 화면에서 그 줄을 숨긴다).
     */
    private static Integer lowestPriceOf(Performance performance, List<GradePriceRow> gradePrices) {
        return gradePrices.stream()
                .mapToInt(GradePriceRow::getPrice)
                .min()
                .stream().boxed().findFirst()
                .orElse(performance.getBasePrice());
    }

    /**
     * 아직 열리지 않은 회차 중 가장 빠른 것.
     *
     * <p><b>단순히 첫 번째 회차를 쓰면 안 된다</b> — 공연 기간이 이미 시작된 공연은 앞쪽 회차가
     * 지나 있어서, "가장 빠른 회차"라며 어제 날짜를 보여주게 된다. 전부 지났으면 null.
     */
    private static PerformanceSchedule nextScheduleOf(List<PerformanceSchedule> schedules, LocalDateTime now) {
        return schedules.stream()
                .filter(s -> !s.isPast(now))
                .findFirst()
                .orElse(null);
    }

    /** 보여줄 게 하나라도 있는지. 아무것도 없으면 카드를 통째로 숨긴다. */
    public boolean hasAnything() {
        return lowestPrice != null || nextSchedule != null;
    }
}
