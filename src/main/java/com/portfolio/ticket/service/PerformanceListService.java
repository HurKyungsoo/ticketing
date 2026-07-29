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

    public record Result(
            List<PerformanceListRow> performances,
            long total,
            int page,
            int totalPages,
            List<Integer> pageWindow,
            List<Option> categories,
            List<Option> months,
            List<Option> venues,
            /** 결과 0건이 필터 탓이 아니라 DB 자체가 비어서인지. 관리자용 수집 안내를 띄울지 판단하는 데만 쓴다. */
            boolean libraryEmpty
    ) {}

    public Result search(String category, Integer month, String timeSlot, String status,
                          String venue, String region, String keyword, int page) {
        int safePage = Math.max(0, page);
        PerformanceFilter filter = buildFilter(category, month, timeSlot, status, venue, region, keyword, safePage);

        List<PerformanceListRow> rows = performanceMapper.selectPerformances(filter);
        long total = performanceMapper.countPerformances(filter);
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));

        List<Option> categories = buildCategoryOptions(filter, category);
        List<Option> months = buildMonthOptions(filter, month);
        List<Option> venues = buildVenueOptions(filter, venue);
        List<Integer> pageWindow = buildPageWindow(safePage, totalPages);

        return new Result(rows, total, safePage, totalPages, pageWindow, categories, months, venues,
                isLibraryEmpty(total));
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

    private PerformanceFilter buildFilter(String category, Integer month, String timeSlot, String status,
                                           String venue, String region, String keyword, int page) {
        PerformanceFilter filter = new PerformanceFilter();
        filter.setCategory(category);
        filter.setMonth(month);
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
            options.add(new Option(row.getLabel(), row.getLabel(), row.getCount(), row.getLabel().equals(selected)));
            shown += row.getCount();
        }
        long rest = rows.stream().mapToLong(FacetCountRow::getCount).sum() - shown;
        if (rest > 0) {
            options.add(new Option(null, "기타", rest, false));
        }
        return options;
    }

    private PerformanceFilter copyWithout(PerformanceFilter source, Consumer<PerformanceFilter> mutator) {
        PerformanceFilter copy = new PerformanceFilter();
        copy.setCategory(source.getCategory());
        copy.setMonth(source.getMonth());
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
