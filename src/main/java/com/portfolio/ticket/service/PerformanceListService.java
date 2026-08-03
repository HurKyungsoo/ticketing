package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.mapper.PerformanceMapper;
import com.portfolio.ticket.mapper.dto.FacetCountRow;
import com.portfolio.ticket.mapper.dto.PerformanceFilter;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 공연 목록 화면의 필터 조합 + 옵션별 건수 집계를 한 곳에서 처리한다.
 * PerformanceController 는 요청 파라미터 파싱/응답 변환만 하고, 조립은 여기서 한다
 * (CLAUDE.md: 컨트롤러에 비즈니스 로직 작성 금지).
 */
@Service
@RequiredArgsConstructor
public class PerformanceListService {

    private static final int PAGE_SIZE = 12;
    private static final int MONTH_CHIP_COUNT = 6;
    /** 지역 미지정(전국) 일 때는 상위 8개만 — 서울만 해도 공연장이 100곳 넘어서 안 거르면 목록이 안 쓸만해진다. */
    private static final int VENUE_TOP_N_NATIONWIDE = 8;
    /** 지역을 고르면 그 지역 안에서는 사실상 다 보여준다 (서울이 제일 많아도 100여 곳 수준이라 select 로 감당됨). */
    private static final int VENUE_TOP_N_IN_REGION = 150;
    private static final int PAGE_WINDOW = 5;

    private final PerformanceMapper performanceMapper;

    /** 필터 탭/칩/드롭다운 한 항목. value 가 null 이면 그 자체로는 필터링할 수 없는 항목("기타" 등). */
    public record Option(String value, String label, long count, boolean selected) {}

    /**
     * 목록 정렬 갈래. 실제 ORDER BY 는 매퍼의 {@code orderBy} 조각이 이 코드값을
     * {@code <choose>} 로 비교해서 고른다 — 코드값을 SQL 에 이어붙이지 않는다.
     *
     * <p>정렬 축을 <b>공연 테이블 컬럼으로만</b> 잡았다. "인기순"은 넣지 않았는데,
     * 예매 수를 세는 집계라 실사용 데이터가 있어야 의미가 생기고 지금은 대부분 0이라
     * 전부 동점이 되어 2차 정렬로 무너진다 — 고를 수는 있는데 결과가 안 바뀌는
     * 정렬은 없는 것보다 나쁘다. 실제 예매가 쌓이면 그때 추가한다.
     */
    public enum Sort {
        RECOMMENDED("recommended", "추천순"),
        CLOSING("closing", "마감 임박순"),
        NEWEST("newest", "최신 등록순"),
        PRICE_ASC("priceAsc", "낮은 가격순");

        private final String code;
        private final String label;

        Sort(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }

        /** 알 수 없는 값(사용자가 URL 을 고쳤거나 링크가 낡았을 때)은 조용히 기본 정렬로 떨어뜨린다. */
        public static Sort from(String code) {
            for (Sort s : values()) {
                if (s.code.equals(code)) return s;
            }
            return RECOMMENDED;
        }
    }

    public record Result(
            List<PerformanceListRow> performances,
            long total,
            int page,
            int totalPages,
            List<Integer> pageWindow,
            List<Option> categories,
            List<Option> months,
            List<Option> venues,
            /** 정렬 드롭다운. 현재 고른 갈래는 {@code selected} 로 표시된다. */
            List<Option> sorts,
            /** 결과 0건이 필터 탓이 아니라 DB 자체가 비어서인지. 관리자용 수집 안내를 띄울지 판단하는 데만 쓴다. */
            boolean libraryEmpty
    ) {}

    public Result search(String category, Integer month, String dayOfWeek, String timeSlot, String status,
                          String venue, String region, String keyword, String sort, int page) {
        int safePage = Math.max(0, page);
        Sort resolvedSort = Sort.from(sort);
        PerformanceFilter filter =
                buildFilter(category, month, dayOfWeek, timeSlot, status, venue, region, keyword,
                        resolvedSort, safePage);

        List<PerformanceListRow> rows = performanceMapper.selectPerformances(filter);
        long total = performanceMapper.countPerformances(filter);
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));

        List<Option> categories = buildCategoryOptions(filter, category);
        List<Option> months = buildMonthOptions(filter, month);
        List<Option> venues = buildVenueOptions(filter, venue);
        List<Integer> pageWindow = buildPageWindow(safePage, totalPages);

        return new Result(rows, total, safePage, totalPages, pageWindow, categories, months, venues,
                buildSortOptions(resolvedSort), isLibraryEmpty(total));
    }

    /** 정렬은 결과를 거르지 않으므로 건수는 의미가 없다. Option 을 재사용하되 count 는 0 으로 둔다. */
    private List<Option> buildSortOptions(Sort selected) {
        List<Option> options = new ArrayList<>();
        for (Sort s : Sort.values()) {
            options.add(new Option(s.code(), s.label(), 0, s == selected));
        }
        return options;
    }

    /**
     * 0건인 이유가 "필터에 안 걸림" 인지 "수집된 공연이 아예 없음" 인지 가른다.
     * 구분하지 않으면 단순 검색 실패에도 관리자용 수집 안내가 일반 사용자에게 노출된다.
     * 0건일 때만 한 번 더 세므로 평상시에는 추가 쿼리가 나가지 않는다.
     */
    private boolean isLibraryEmpty(long total) {
        return total == 0 && performanceMapper.countPerformances(new PerformanceFilter()) == 0;
    }

    /** 현재 페이지를 가운데 두고 최대 PAGE_WINDOW 개만 보여준다 (전체 페이지 수가 많아도 번호가 안 늘어나게). */
    private List<Integer> buildPageWindow(int page, int totalPages) {
        int start = Math.max(0, Math.min(page - PAGE_WINDOW / 2, totalPages - PAGE_WINDOW));
        int end = Math.min(totalPages - 1, start + PAGE_WINDOW - 1);
        List<Integer> window = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            window.add(i);
        }
        return window;
    }

    private PerformanceFilter buildFilter(String category, Integer month, String dayOfWeek, String timeSlot,
                                           String status, String venue, String region, String keyword,
                                           Sort sort, int page) {
        PerformanceFilter filter = new PerformanceFilter();
        filter.setSort(sort.code());
        filter.setCategory(category);
        filter.setMonth(month);
        applyDayOfWeek(filter, dayOfWeek);
        applyTimeSlot(filter, timeSlot);
        // 매퍼는 "ONGOING"/"ENDED" 대문자로만 비교한다. 쿼리 파라미터는 소문자("ongoing")로
        // 들어오므로 여기서 맞춰준다 — 안 맞추면 조건문이 통째로 빠져 필터가 무시된다.
        filter.setStatus(status == null ? null : status.toUpperCase(Locale.ROOT));
        filter.setToday(LocalDate.now());
        filter.setVenue(venue);
        filter.setRegion(region);
        filter.setKeyword(keyword);
        filter.setOffset(page * PAGE_SIZE);
        filter.setLimit(PAGE_SIZE);
        return filter;
    }

    /**
     * 요일 코드를 SQL {@code DAYOFWEEK()} 값(1=일 … 7=토) 목록으로 펼친다.
     *
     * <p>주중/주말을 따로 둔 건 실데이터 분포 때문이다 — 388건을 세어보니 주말에만 하는
     * 공연이 48.5%, 평일에만 하는 공연이 33.8% 로 두 덩어리가 뚜렷했다. 개별 요일도
     * 남겨둔다(월요일은 4.4% 라 오히려 개별 선택이 잘 걸린다).
     */
    private void applyDayOfWeek(PerformanceFilter filter, String dayOfWeek) {
        if (dayOfWeek == null) return;
        List<Integer> days = switch (dayOfWeek) {
            case "weekday" -> List.of(2, 3, 4, 5, 6);   // 월~금
            case "weekend" -> List.of(1, 7);            // 일, 토
            case "sun" -> List.of(1);
            case "mon" -> List.of(2);
            case "tue" -> List.of(3);
            case "wed" -> List.of(4);
            case "thu" -> List.of(5);
            case "fri" -> List.of(6);
            case "sat" -> List.of(7);
            // 알 수 없는 값은 무시(전체 취급) — 잘못된 쿼리 파라미터로 화면이 깨지면 안 된다.
            default -> null;
        };
        filter.setDaysOfWeek(days);
    }

    /** 자정 상한은 23:59:59 로 넘긴다 — H2/MySQL 은 TIME 리터럴로 "24:00:00" 을 못 받는다. */
    private void applyTimeSlot(PerformanceFilter filter, String timeSlot) {
        if (timeSlot == null) return;
        switch (timeSlot) {
            case "morning" -> {
                filter.setTimeSlotFrom(LocalTime.of(6, 0));
                filter.setTimeSlotTo(LocalTime.of(12, 0));
            }
            case "afternoon" -> {
                filter.setTimeSlotFrom(LocalTime.of(12, 0));
                filter.setTimeSlotTo(LocalTime.of(18, 0));
            }
            case "evening" -> {
                filter.setTimeSlotFrom(LocalTime.of(18, 0));
                filter.setTimeSlotTo(LocalTime.of(23, 59, 59));
            }
            default -> {
                // 알 수 없는 값은 무시(전체 취급) — 잘못된 쿼리 파라미터로 화면이 깨지면 안 된다.
            }
        }
    }

    /** category 필터는 빼고 나머지 조건만 반영해서, 다른 카테고리를 골랐을 때의 건수를 보여준다. */
    private List<Option> buildCategoryOptions(PerformanceFilter filter, String selected) {
        PerformanceFilter withoutCategory = copyWithout(filter, f -> f.setCategory(null));
        Map<String, Long> counts = performanceMapper.selectCategoryCounts(withoutCategory).stream()
                .collect(Collectors.toMap(FacetCountRow::getLabel, FacetCountRow::getCount));

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Option> options = new ArrayList<>();
        options.add(new Option("all", "전체", total, selected == null));
        for (PerformanceCategory c : PerformanceCategory.values()) {
            options.add(new Option(c.name(), c.label(), counts.getOrDefault(c.name(), 0L), c.name().equals(selected)));
        }
        return options;
    }

    /** 현재 달부터 6개월치 고정 칩. month 필터는 빼고 나머지 조건만 반영해서 건수를 구한다. */
    private List<Option> buildMonthOptions(PerformanceFilter filter, Integer selected) {
        PerformanceFilter withoutMonth = copyWithout(filter, f -> f.setMonth(null));
        Map<Integer, Long> counts = performanceMapper.selectMonthCounts(withoutMonth).stream()
                .collect(Collectors.toMap(r -> Integer.valueOf(r.getLabel()), FacetCountRow::getCount));

        List<Option> options = new ArrayList<>();
        LocalDate cursor = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i < MONTH_CHIP_COUNT; i++) {
            int m = cursor.getMonthValue();
            options.add(new Option(String.valueOf(m), m + "월", counts.getOrDefault(m, 0L), Integer.valueOf(m).equals(selected)));
            cursor = cursor.plusMonths(1);
        }
        return options;
    }

    /**
     * 건수 상위 N개 + "기타"(나머지 합). "기타"는 특정 공연장이 아니라서 value 없이 건수만 보여준다.
     * 지역이 정해져 있으면 그 지역 안에서 집계되므로(withoutVenue 가 region 은 그대로 반영) N 을
     * 크게 잡아서 사실상 전부 보여준다 — 지역별로 좁혀진 목록에서까지 "기타"에 묻히면 못 고른다.
     */
    private List<Option> buildVenueOptions(PerformanceFilter filter, String selected) {
        PerformanceFilter withoutVenue = copyWithout(filter, f -> f.setVenue(null));
        List<FacetCountRow> rows = performanceMapper.selectVenueCounts(withoutVenue);

        int topN = filter.getRegion() != null ? VENUE_TOP_N_IN_REGION : VENUE_TOP_N_NATIONWIDE;
        List<Option> options = new ArrayList<>();
        long shown = 0;
        int n = Math.min(topN, rows.size());
        for (int i = 0; i < n; i++) {
            FacetCountRow row = rows.get(i);
            // 쿼리에서 이미 걸렀지만, 다른 소스가 빈 공연장명을 들고 와도 화면 전체가 죽지는 않도록 둔다.
            options.add(new Option(row.getLabel(), row.getLabel(), row.getCount(),
                    Objects.equals(row.getLabel(), selected)));
            shown += row.getCount();
        }
        long rest = rows.stream().mapToLong(FacetCountRow::getCount).sum() - shown;
        if (rest > 0) {
            options.add(new Option(null, "기타", rest, false));
        }
        return options;
    }

    /**
     * 패싯 건수를 낼 때 자기 축만 빼고 나머지 조건을 그대로 복사한다.
     * <b>새 필터 필드를 추가하면 여기에도 반드시 넣어야 한다</b> — 빠뜨리면 예외 없이
     * 그 조건만 무시된 건수가 나와서, 화면 숫자와 실제 결과가 조용히 어긋난다.
     *
     * <p>{@code sort} 는 일부러 뺐다. 정렬은 행을 거르지 않아 건수에 영향이 없고,
     * 패싯 쿼리들은 {@code orderBy} 조각을 아예 포함하지 않는다.
     */
    private PerformanceFilter copyWithout(PerformanceFilter source, Consumer<PerformanceFilter> mutator) {
        PerformanceFilter copy = new PerformanceFilter();
        copy.setCategory(source.getCategory());
        copy.setMonth(source.getMonth());
        copy.setDaysOfWeek(source.getDaysOfWeek());
        copy.setTimeSlotFrom(source.getTimeSlotFrom());
        copy.setTimeSlotTo(source.getTimeSlotTo());
        copy.setStatus(source.getStatus());
        copy.setToday(source.getToday());
        copy.setVenue(source.getVenue());
        copy.setRegion(source.getRegion());
        copy.setKeyword(source.getKeyword());
        mutator.accept(copy);
        return copy;
    }
}
