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
     * 다음 회차(위 nextShowAt)의 잔여석. 예매 가능 여부 배지(매진/매진임박)에 쓴다.
     * nextShowAt 이 null 이면 이 값도 의미가 없다(볼 회차 자체가 없다).
     */
    private Integer nextRemainingSeats;

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
    public NextShow nextShow(LocalDate today) {
        return nextShowFor(nextShowAt, today);
    }

    /**
     * 카드의 회차 알약에 넣을 것.
     *
     * @param text 알약 안에 적을 문구. 「다음 공연」 같은 머리말은 안 붙인다 — 알약이라는
     *             모양 자체가 그 뜻을 하고, 좁은 카드에서 알약이 길어지면 오히려 안 읽힌다.
     *             스크린리더용 머리말은 화면이 {@code .sr-only} 로 따로 붙인다.
     * @param soon 오늘이나 내일이냐. 화면이 이때만 알약을 브라스로 <b>채운다</b> — 색은
     *             "지금 급한 것"에만 쓴다는 이 프로젝트의 규칙에 맞춘다(마감 임박 배지가
     *             로즈를 그렇게 쓴다). 전부 채우면 카드마다 브라스가 깔려 뜻이 사라진다.
     */
    public record NextShow(String text, boolean soon) {}

    /**
     * 위와 같은 값을 이 DTO 없이도 만들 수 있게 뺀 것. 찜 목록은 카드가 JPA 엔티티라
     * 이 DTO 를 안 거치는데, 거기만 표기가 다르면 같은 모양의 카드가 화면에 따라 다르게
     * 읽힌다.
     */
    public static NextShow nextShowFor(LocalDateTime nextShowAt, LocalDate today) {
        if (nextShowAt == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(today, nextShowAt.toLocalDate());
        if (days == 0) {
            return new NextShow("오늘 " + nextShowAt.format(TIME_ONLY), true);
        }
        if (days == 1) {
            return new NextShow("내일 " + nextShowAt.format(TIME_ONLY), true);
        }
        return new NextShow(nextShowAt.format(NEXT_SHOW), false);
    }

    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 예매 가능 여부 배지에 잔여석이 이 이하면 "매진임박". 비율이 아니라 절대 개수를 쓴다 —
     * 대극장의 10% 는 수백 석이라 절대 임박이 아니고, 소극장의 절대 10석은 이미 없다시피
     * 하다. 실측 대신 마감 임박 배지(daysUntilClose &lt;= 7)와 같은 종류의 판단으로,
     * 국내 예매처가 흔히 쓰는 "잔여 N석 이하" 표기 관례를 그대로 따랐다.
     */
    private static final int ALMOST_SOLD_OUT_THRESHOLD = 10;

    /**
     * 예매 가능 여부. 화면이 배지로 그린다 — 없으면(null) "예매 가능"이라 배지를 안 그린다
     * (D-day 배지가 급하지 않을 때 안 그리는 것과 같은 관례. 색은 "지금 알아야 할 것"에만
     * 쓴다).
     *
     * <p><b>다음 회차(next showing)의 잔여석만 본다.</b> 그 회차가 매진이어도 더 뒤 회차에
     * 자리가 있을 수 있지만, 그 경우까지 훑으면 배지가 "예매 가능한 회차 중 가장 가까운
     * 것"을 말하게 되어 카드에 적힌 다음 회차 날짜와 배지가 서로 다른 날을 가리키게 된다.
     * 배지는 항상 화면에 보이는 그 날짜에 대한 것이어야 한다 — 클릭해서 상세로 들어가면
     * 다른 회차의 잔여석은 회차 선택 화면에서 그대로 볼 수 있다.
     *
     * @param today "아직 시작 전(오픈 예정)"과 "이미 하고 있는데 회차 정보가 없음(예매처
     *              정보 없음)"을 가르는 기준일. daysUntilClose 와 같은 이유로 인자로 받는다.
     */
    public Availability availability(LocalDate today) {
        if (nextShowAt == null) {
            if (startDate != null && today.isBefore(startDate)) {
                return new Availability("오픈 예정", "avail-pending");
            }
            return new Availability("예매처 정보 없음", "avail-unknown");
        }
        if (nextRemainingSeats != null) {
            if (nextRemainingSeats <= 0) {
                return new Availability("매진", "avail-soldout");
            }
            if (nextRemainingSeats <= ALMOST_SOLD_OUT_THRESHOLD) {
                return new Availability("매진임박", "avail-low");
            }
        }
        return null;
    }

    /** 예매 가능 여부 배지에 넣을 것. cssClass 는 상태별 색을 가르는 데 쓴다. */
    public record Availability(String label, String cssClass) {}

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M.d");
    private static final DateTimeFormatter YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("yyyy.M.d");

    /** 공연 기간. {@link #periodFor} 참고. */
    public String runPeriod(LocalDate today) {
        return periodFor(startDate, endDate, today);
    }

    /**
     * "6.4 – 8.30" 같은 공연 기간.
     *
     * <p>다음 회차만 적으면 <b>이 공연이 언제까지 하는지</b>를 알 수 없다. 회차는 "언제
     * 가지"에, 기간은 "언제까지 볼 수 있지"에 답하는 서로 다른 정보라 둘 다 필요하다.
     *
     * <p><b>연도는 바뀔 때만 적는다.</b> 종전 표기 {@code 2026.06.04 – 2026.08.30} 은
     * 23자에 연도가 두 번 들어가서, 카드 폭에서 줄바꿈되거나 잘렸다. 규칙은 세 가지다:
     * <ul>
     *   <li>둘 다 올해면 아예 안 적는다 — {@code 6.4 – 8.30}</li>
     *   <li>같은 해지만 올해가 아니면 앞에만 — {@code 2027.3.1 – 4.1}</li>
     *   <li>해를 넘기면 양쪽에 — {@code 2026.12.20 – 2027.1.5}</li>
     * </ul>
     * 둘 다 올해일 때만 지우는 게 핵심이다. "같은 해면 생략"으로 두면 12월에 보는
     * 내년 공연이 연도 없이 {@code 3.1 – 4.1} 로 적혀 올해 것처럼 읽힌다.
     */
    public static String periodFor(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null || end == null) {
            return null;
        }
        boolean bothThisYear = start.getYear() == today.getYear() && end.getYear() == today.getYear();
        DateTimeFormatter head = bothThisYear ? MONTH_DAY : YEAR_MONTH_DAY;
        DateTimeFormatter tail = start.getYear() == end.getYear() ? MONTH_DAY : YEAR_MONTH_DAY;
        return start.format(head) + " – " + end.format(tail);
    }
}
