package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SeatGrade;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS 상세의 공연 시각(dtguidance)·가격(pcseguidance) 문구 해석.
 *
 * <p>수집 때만 쓰던 것을 저장된 원문으로 회차를 다시 만들 때도 쓰려고 클라이언트에서 떼어 냈다.
 * 외부 호출이 없는 순수 함수라 어디서 불러도 같은 결과가 나온다.
 */
public final class KopisGuidanceParser {

    private KopisGuidanceParser() {
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Map<Character, DayOfWeek> DAY_CHAR = Map.of(
            '월', DayOfWeek.MONDAY, '화', DayOfWeek.TUESDAY, '수', DayOfWeek.WEDNESDAY,
            '목', DayOfWeek.THURSDAY, '금', DayOfWeek.FRIDAY, '토', DayOfWeek.SATURDAY, '일', DayOfWeek.SUNDAY);

    /**
     * 실제 dtguidance 예: "화요일(19:30), 금요일(19:30)" / "토요일(11:00,14:00)".
     * 반드시 "요일" 전체를 붙여서 찾는다 — "화요일"의 "일"만 보면 일요일로 오매칭된다.
     */
    private static final Pattern DAY_TIME_PATTERN =
            Pattern.compile("(월|화|수|목|금|토|일)요일\\s*\\(([^)]*)\\)");
    /** "목요일 ~ 금요일(10:30)" 같은 요일 범위. */
    private static final Pattern DAY_RANGE_TIME_PATTERN =
            Pattern.compile("(월|화|수|목|금|토|일)요일\\s*~\\s*(월|화|수|목|금|토|일)요일\\s*\\(([^)]*)\\)");
    private static final Pattern EVERYDAY_TIME_PATTERN = Pattern.compile("매일\\s*\\(([^)]*)\\)");

    /** "VIP석 100,000원" / "R 80000원" 처럼 등급 문자 뒤에 붙는 가격을 뽑는다. B 는 SeatGrade 에 없어 버린다. */
    /**
     * "전석 30,000원". 표본 40건 중 절반 넘게 이 형태였다 — 등급 구분이 없는 공연이다.
     * 뒤에 "사전예약 15,000원" 같은 할인가가 붙어도 정가인 전석 가격을 쓴다.
     */
    private static final Pattern ALL_SEATS_PRICE_PATTERN = Pattern.compile("전석\\s*([\\d,]{3,})\\s*원");

    private static final Pattern GRADE_PRICE_PATTERN =
            Pattern.compile("(VIP|R|S|A|B)\\s*석?\\s*([\\d,]{3,})\\s*원");

    /**
     * 실제 dtguidance 예: "화요일(19:30), 금요일(19:30)" / "토요일(11:00,14:00)" /
     * "목요일 ~ 금요일(10:30)" / "매일(19:00)". 세 패턴을 각각 매칭하고 요일별로 시각을
     * Set 에 모아 중복을 제거한다 — 범위 매칭과 단일 매칭이 같은 요일을 중복으로 잡을 수 있어서
     * (예: "목요일 ~ 금요일(10:30)"의 금요일이 단일 패턴에도 잡힘) 중복 제거가 꼭 필요하다.
     * (안 하면 같은 회차가 두 번 만들어지려다 유니크 제약 위반으로 배치가 죽는다.)
     * 요일/시각을 하나도 못 읽으면 null 을 반환해 호출부가 기존 8일 고정 규칙으로 대체하게 한다.
     */
    public static Map<DayOfWeek, List<LocalTime>> showTimes(String raw) {
        if (raw == null || raw.isBlank()) return null;

        Map<DayOfWeek, Set<LocalTime>> byDay = new EnumMap<>(DayOfWeek.class);

        Matcher everyday = EVERYDAY_TIME_PATTERN.matcher(raw);
        while (everyday.find()) {
            addTimes(byDay, EnumSet.allOf(DayOfWeek.class), extractTimes(everyday.group(1)));
        }

        Matcher range = DAY_RANGE_TIME_PATTERN.matcher(raw);
        while (range.find()) {
            addTimes(byDay, expandRange(range.group(1).charAt(0), range.group(2).charAt(0)),
                    extractTimes(range.group(3)));
        }

        Matcher single = DAY_TIME_PATTERN.matcher(raw);
        while (single.find()) {
            DayOfWeek day = DAY_CHAR.get(single.group(1).charAt(0));
            if (day != null) {
                addTimes(byDay, EnumSet.of(day), extractTimes(single.group(2)));
            }
        }

        if (byDay.isEmpty()) return null;
        Map<DayOfWeek, List<LocalTime>> result = new EnumMap<>(DayOfWeek.class);
        byDay.forEach((day, times) -> result.put(day, new ArrayList<>(times)));
        return result;
    }

    private static void addTimes(Map<DayOfWeek, Set<LocalTime>> byDay, Set<DayOfWeek> days, List<LocalTime> times) {
        if (times.isEmpty()) return;
        for (DayOfWeek day : days) {
            byDay.computeIfAbsent(day, d -> new LinkedHashSet<>()).addAll(times);
        }
    }

    /** 월~일 순서가 DayOfWeek 순서와 같다는 걸 이용해 범위를 펼친다. 역방향(토~월 등)은 지원하지 않고 건너뛴다. */
    private static Set<DayOfWeek> expandRange(char fromChar, char toChar) {
        DayOfWeek from = DAY_CHAR.get(fromChar);
        DayOfWeek to = DAY_CHAR.get(toChar);
        if (from == null || to == null || from.getValue() > to.getValue()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (int v = from.getValue(); v <= to.getValue(); v++) {
            days.add(DayOfWeek.of(v));
        }
        return days;
    }

    private static List<LocalTime> extractTimes(String segment) {
        List<LocalTime> times = new ArrayList<>();
        Matcher matcher = TIME_PATTERN.matcher(segment);
        while (matcher.find()) {
            try {
                times.add(LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
            } catch (Exception ignored) {
                // 24:00 등 범위를 벗어나는 값은 건너뛰고 다음 매치를 시도한다.
            }
        }
        return times;
    }

    /**
     * "VIP석 100,000원 R석 80,000원 S석 60,000원" 같은 자유 텍스트를 등급별로 파싱한다.
     * 등급을 하나도 못 읽으면 null 을 반환해 호출부가 basePrice 비율 계산으로 대체하게 한다.
     */
    public static Map<SeatGrade, Integer> pricesByGrade(String raw) {
        if (raw == null || raw.isBlank()) return null;

        Map<SeatGrade, Integer> result = new EnumMap<>(SeatGrade.class);
        Matcher matcher = GRADE_PRICE_PATTERN.matcher(raw);
        while (matcher.find()) {
            String gradeToken = matcher.group(1);
            if ("B".equals(gradeToken)) continue;

            try {
                SeatGrade grade = SeatGrade.valueOf(gradeToken);
                int price = Integer.parseInt(matcher.group(2).replace(",", ""));
                result.put(grade, price);
            } catch (Exception ignored) {
                // 다음 매치를 시도한다.
            }
        }
        if (result.isEmpty()) {
            // 등급 구분 없이 한 가격인 공연. 좌석은 한 등급(S석)으로 만든다 — S석의 기본 비율이 1.0 이라
            // 원문이 없을 때의 기준가 계산과도 어긋나지 않는다.
            Matcher allSeats = ALL_SEATS_PRICE_PATTERN.matcher(raw);
            if (allSeats.find()) {
                try {
                    result.put(SeatGrade.S, Integer.parseInt(allSeats.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                    // 숫자로 읽을 수 없으면 등급 정보 없음으로 둔다.
                }
            }
        }
        return result.isEmpty() ? null : result;
    }
}
