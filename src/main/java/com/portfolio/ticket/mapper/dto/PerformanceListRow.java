package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Getter
@Setter
public class PerformanceListRow {
    private Long id;
    private String title;
    private String venue;
    private String posterUrl;
    private String category;
    private LocalDate startDate;
    private LocalDate endDate;

    /**
     * 종료일까지 남은 일수. 홈 카드의 D-day 배지에 쓴다. 오늘 끝나면 0 이다.
     *
     * <p>기준일을 인자로 받는다 — 안에서 {@code LocalDate.now()} 를 부르면 같은 화면 안에서도
     * 자정을 넘기는 순간 카드마다 다른 날짜를 기준으로 세게 되고, 테스트에서 값을 고정할 수도 없다.
     * 홈은 {@code Home.today} 한 값을 모든 카드에 넘겨 화면 전체가 같은 기준을 쓴다.
     *
     * <p>홈은 진행·예정작만 담으므로 정상 흐름에서 음수가 나오지는 않지만, 막지는 않는다 —
     * 표시 여부는 화면이 정할 일이고 여기서 0 으로 깎으면 "오늘 마감"과 구분이 사라진다.
     */
    public long daysUntilClose(LocalDate today) {
        return ChronoUnit.DAYS.between(today, endDate);
    }
}
