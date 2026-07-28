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
    private static final int VENUE_TOP_N = 8;

    private final PerformanceMapper performanceMapper;

    /** 필터 탭/칩/드롭다운 한 항목. value 가 null 이면 그 자체로는 필터링할 수 없는 항목("기타" 등). */
    public record Option(String value, String label, long count, boolean selected) {}

    public record Result(
            List<PerformanceListRow> performances,
            long total,
            int page,
            int totalPages,
            List<Option> categories,
            List<Option> months,
            List<Option> venues
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

        return new Result(rows, total, safePage, totalPages, categories, months, venues);
    }

    private PerformanceFilter buildFilter(String category, Integer month, String timeSlot, String status,
                                           String venue, String region, String keyword, int page) {
        PerformanceFilter filter = new PerformanceFilter();
        filter.setCategory(category);
        filter.setMonth(month);
        applyTimeSlot(filter, timeSlot);
        filter.setStatus(status);
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

    /** 건수 상위 N개 + "기타"(나머지 합). "기타"는 특정 공연장이 아니라서 value 없이 건수만 보여준다. */
    private List<Option> buildVenueOptions(PerformanceFilter filter, String selected) {
        PerformanceFilter withoutVenue = copyWithout(filter, f -> f.setVenue(null));
        List<FacetCountRow> rows = performanceMapper.selectVenueCounts(withoutVenue);

        List<Option> options = new ArrayList<>();
        long shown = 0;
        int n = Math.min(VENUE_TOP_N, rows.size());
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
