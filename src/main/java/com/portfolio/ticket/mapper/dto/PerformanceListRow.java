package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

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
     * 오늘 이후로 가장 가까운 회차. 없으면 null(남은 회차가 다 지난 공연).
     *
     * <p>매퍼가 상관 서브쿼리로 같이 뽑는다 — 임박순 정렬이 쓰는 것과 같은 값이다.
     * 필터에 월·요일·시간대가 걸려 있으면 그 조건에 맞는 회차 중에서 고른다:
     * "8월 공연"을 보고 있는데 카드에 9월 회차가 적히면 안 된다.
     */
    private LocalDateTime nextShowAt;

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

    /**
     * 요일(E)은 로케일을 따라가므로 한국어로 못박는다. 안 박으면 JVM 기본 로케일을 쓰는데,
     * 배포 서버(Ubuntu)는 대개 en_US 라 「8/27(수)」가 「8/27(Wed)」로 나간다 — 로컬에서는
     * 한국어 윈도우라 멀쩡해 보여서 배포하고 나서야 드러날 종류의 차이다.
     */
    private static final DateTimeFormatter NEXT_SHOW =
            DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN);

    /**
     * 카드에 적을 "언제 볼 수 있나". 남은 회차가 없으면 null 이고, 그때는 화면이
     * 종전대로 공연 기간을 적는다.
     *
     * <p>카드가 적던 게 {@code 2026.06.04 – 2026.08.30} 같은 <b>공연 기간</b>이었는데,
     * 석 달짜리 범위는 "언제 가지?"에 아무 대답이 안 된다. 실제로 필요한 건 다음 회차 하나다.
     *
     * <p>오늘·내일은 날짜 대신 말로 적는다 — {@code 8/25(월) 19:30} 을 읽고 그게 오늘인지
     * 알려면 오늘 날짜를 떠올려 비교해야 한다. 가장 급한 두 경우에서 그 계산을 없앤다.
     * 그 밖에는 무엇에 대한 날짜인지 알 수 있게 「다음 공연」을 붙인다.
     *
     * <p>기준일을 인자로 받는 이유는 {@link #daysUntilClose(LocalDate)} 와 같다.
     */
    public String nextShowLabel(LocalDate today) {
        return labelFor(nextShowAt, today);
    }

    /**
     * 위와 같은 문구를 이 DTO 없이도 만들 수 있게 뺀 것. 찜 목록은 카드가 JPA 엔티티라
     * 이 DTO 를 안 거치는데, 거기만 문구가 다르면 같은 모양의 카드가 화면에 따라 다르게
     * 읽힌다.
     */
    public static String labelFor(LocalDateTime nextShowAt, LocalDate today) {
        if (nextShowAt == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(today, nextShowAt.toLocalDate());
        if (days == 0) {
            return "오늘 " + nextShowAt.format(TIME_ONLY);
        }
        if (days == 1) {
            return "내일 " + nextShowAt.format(TIME_ONLY);
        }
        return "다음 공연 " + nextShowAt.format(NEXT_SHOW);
    }

    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm");
}
