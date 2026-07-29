package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.SeatMapRow;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.service.PerformanceListService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class PerformanceController {

    /** KOPIS area 필드 값과 정확히 맞춰야 지역 필터의 exact-match 가 걸린다. */
    private static final List<String> REGIONS = List.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시",
            "세종특별자치시", "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
            "경상북도", "경상남도", "제주특별자치도");

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatMapper seatMapper;
    private final PerformanceListService performanceListService;

    /**
     * 필터는 전부 쿼리 파라미터로 관리하고 "all" 을 기본 센티넬로 쓴다 — 파라미터가 항상
     * 존재해야 필터를 바꾸거나 페이지를 넘길 때 다른 조건이 URL 에서 유실되지 않는다.
     */
    @GetMapping("/")
    public String list(@RequestParam(defaultValue = "all") String genre,
                        @RequestParam(defaultValue = "all") String month,
                        @RequestParam(defaultValue = "all") String timeSlot,
                        @RequestParam(defaultValue = "ongoing") String status,
                        @RequestParam(defaultValue = "all") String venue,
                        @RequestParam(defaultValue = "all") String area,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        PerformanceListService.Result result = performanceListService.search(
                sentinel(genre), parseMonth(month), sentinel(timeSlot), sentinel(status),
                sentinel(venue), sentinel(area), blankToNull(keyword), page);

        model.addAttribute("result", result);
        model.addAttribute("regions", REGIONS);
        // 필터 폼/링크가 현재 선택값을 그대로 다시 뿌릴 수 있도록 원본 파라미터 문자열도 넘긴다.
        model.addAttribute("genre", genre);
        model.addAttribute("month", month);
        model.addAttribute("timeSlot", timeSlot);
        model.addAttribute("status", status);
        model.addAttribute("venue", venue);
        model.addAttribute("area", area);
        model.addAttribute("keyword", keyword);
        // "필터 초기화" 를 띄울지 판단용. 하나라도 기본값에서 벗어났을 때만 보여준다.
        model.addAttribute("filtersApplied",
                filtersApplied(genre, month, timeSlot, status, venue, area, keyword));
        return "performance/list";
    }

    /** 기본 상태(장르·월·시간대·공연장·지역 전체 + 진행·예정작 + 검색어 없음)에서 벗어났는지. */
    private boolean filtersApplied(String genre, String month, String timeSlot,
                                    String status, String venue, String area, String keyword) {
        return sentinel(genre) != null
                || parseMonth(month) != null
                || sentinel(timeSlot) != null
                || !"ongoing".equalsIgnoreCase(status)
                || sentinel(venue) != null
                || sentinel(area) != null
                || blankToNull(keyword) != null;
    }

    /** "all"(대소문자 무관) 또는 빈 값은 "필터 없음" 을 뜻하는 null 로 변환한다. */
    private String sentinel(String value) {
        return (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) ? null : value;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** 잘못된 month 파라미터(문자열, 범위 밖 숫자)로 화면이 500 에러 나면 안 되니 안전하게 무시한다. */
    private Integer parseMonth(String month) {
        String resolved = sentinel(month);
        if (resolved == null) return null;
        try {
            int m = Integer.parseInt(resolved);
            return (m >= 1 && m <= 12) ? m : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @GetMapping("/performances/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Performance performance = performanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다."));
        List<PerformanceSchedule> schedules = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(id);

        model.addAttribute("performance", performance);
        model.addAttribute("schedules", schedules);
        return "performance/detail";
    }

    @GetMapping("/schedules/{scheduleId}/seats")
    public String seatMap(@PathVariable Long scheduleId, Model model) {
        PerformanceSchedule schedule = scheduleRepository.findWithPerformanceById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("회차를 찾을 수 없습니다."));
        List<SeatMapRow> seats = seatMapper.selectSeatMap(scheduleId);

        // 구역(A, B, C...) 단위로 묶어서 넘긴다. 템플릿에서 그룹핑하면 정렬이 깨진다.
        Map<String, List<SeatMapRow>> rows = seats.stream()
                .collect(Collectors.groupingBy(SeatMapRow::getSection,
                        LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("schedule", schedule);
        model.addAttribute("performance", schedule.getPerformance());
        model.addAttribute("rows", rows);
        return "reservation/seat-map";
    }
}
