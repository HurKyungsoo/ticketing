package com.portfolio.ticket.controller;

import com.portfolio.ticket.config.NaverMapProperties;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.GradePriceRow;
import com.portfolio.ticket.mapper.dto.SeatMapRow;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.PerformanceListService;
import com.portfolio.ticket.service.PerformanceSummaryView;
import com.portfolio.ticket.service.ScheduleDayView;
import com.portfolio.ticket.service.SeatMapView;
import com.portfolio.ticket.service.SeoView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
    private final NaverMapProperties naverMapProperties;
    private final SeatMapView seatMapView;
    private final SeoView seoView;

    /**
     * 공유 카드용 절대주소의 기준. Thymeleaf 3.1 부터 템플릿에서 {@code #request} 를 못 쓰므로
     * 여기서 만들어 넘긴다. 리버스 프록시 뒤에 두면 {@code X-Forwarded-*} 를 반영하도록
     * {@code server.forward-headers-strategy} 를 켜야 원래 도메인이 나온다.
     */
    private String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /**
     * 홈. 조건을 걸어 찾는 화면(목록)과 뭐가 있나 훑는 화면(홈)을 나눴다 — 종전에는 "/" 가
     * 곧 검색결과 그리드라서, 처음 들어온 사람이 필터부터 마주하고 무엇을 고를지에 대한
     * 실마리가 없었다.
     *
     * <p>필터 파라미터가 하나라도 붙어 오면 목록으로 넘긴다. "/" 가 목록이던 시절의
     * 북마크·외부 링크(예: {@code /?genre=MUSICAL})가 조건을 잃고 홈으로 떨어지면 안 된다.
     */
    @GetMapping("/")
    public String home(@RequestParam Map<String, String> params, Model model) {
        if (!params.isEmpty()) {
            return "redirect:/performances" + queryStringOf(params);
        }
        model.addAttribute("home", performanceListService.home());
        model.addAttribute("seo", seoView.forSite(baseUrl(), "/", "객석 - 공연 예매",
                "공공데이터로 모은 전국 공연을 한곳에서. 실제 좌석 배치도로 자리를 고르고 바로 예매하세요."));
        return "performance/home";
    }

    /** 넘겨받은 파라미터를 그대로 다시 붙인다. 값 검증은 목록 쪽이 어차피 다시 한다. */
    private String queryStringOf(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&", "?", ""));
    }

    /**
     * 필터는 전부 쿼리 파라미터로 관리하고 "all" 을 기본 센티넬로 쓴다 — 파라미터가 항상
     * 존재해야 필터를 바꾸거나 페이지를 넘길 때 다른 조건이 URL 에서 유실되지 않는다.
     */
    @GetMapping("/performances")
    public String list(@RequestParam(defaultValue = "all") String genre,
                        @RequestParam(defaultValue = "all") String month,
                        @RequestParam(defaultValue = "all") String dayOfWeek,
                        @RequestParam(defaultValue = "all") String timeSlot,
                        @RequestParam(defaultValue = "ongoing") String status,
                        @RequestParam(defaultValue = "all") String venue,
                        @RequestParam(defaultValue = "all") String area,
                        @RequestParam(required = false) String keyword,
                        @RequestParam(defaultValue = "recommended") String sort,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        PerformanceListService.Result result = performanceListService.search(
                sentinel(genre), parseMonth(month), sentinel(dayOfWeek), sentinel(timeSlot), sentinel(status),
                sentinel(venue), sentinel(area), blankToNull(keyword), sort, page);

        model.addAttribute("result", result);
        model.addAttribute("regions", REGIONS);
        // 필터 폼/링크가 현재 선택값을 그대로 다시 뿌릴 수 있도록 원본 파라미터 문자열도 넘긴다.
        model.addAttribute("genre", genre);
        model.addAttribute("month", month);
        model.addAttribute("dayOfWeek", dayOfWeek);
        model.addAttribute("timeSlot", timeSlot);
        model.addAttribute("status", status);
        model.addAttribute("venue", venue);
        model.addAttribute("area", area);
        model.addAttribute("keyword", keyword);
        // 알 수 없는 값이 들어와도 화면 드롭다운은 실제 적용된 정렬을 가리켜야 하므로
        // 원본이 아니라 서비스가 확정한 값을 넘긴다.
        model.addAttribute("sort", result.sorts().stream()
                .filter(PerformanceListService.Option::selected)
                .findFirst()
                .map(PerformanceListService.Option::value)
                .orElse("recommended"));
        // "필터 초기화" 를 띄울지 판단용. 하나라도 기본값에서 벗어났을 때만 보여준다.
        model.addAttribute("filtersApplied",
                filtersApplied(genre, month, dayOfWeek, timeSlot, status, venue, area, keyword));
        // canonical 은 필터가 붙지 않은 목록 주소로 고정한다. 필터 조합마다 주소가 달라지는데
        // 내용은 같은 목록이라, 그대로 두면 검색엔진이 사실상 같은 문서를 수십 개로 본다.
        model.addAttribute("seo", seoView.forSite(baseUrl(), "/performances", "공연 목록 - 객석",
                "장르 · 지역 · 날짜로 전국 공연을 찾아보세요."));
        return "performance/list";
    }

    /** 기본 상태(장르·월·요일·시간대·공연장·지역 전체 + 진행·예정작 + 검색어 없음)에서 벗어났는지. */
    private boolean filtersApplied(String genre, String month, String dayOfWeek, String timeSlot,
                                    String status, String venue, String area, String keyword) {
        return sentinel(genre) != null
                || parseMonth(month) != null
                || sentinel(dayOfWeek) != null
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
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));

        LocalDateTime now = LocalDateTime.now();
        // 지난 회차는 목록에서 뺀다. 종전에는 그대로 노출돼서, 기간이 시작된 공연은
        // 이미 끝난 날짜를 누를 수 있었다(예매까지 됐다 — ReservationService 에서 막는다).
        List<PerformanceSchedule> schedules = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(id)
                .stream()
                .filter(s -> !s.isPast(now))
                .toList();

        // 등급별 가격은 회차 아무거나 하나로 대표한다 — 같은 공연의 모든 회차는 SeatGenerator 가
        // 동일한 등급별 가격으로 좌석을 만든다. 회차가 하나도 없으면(수집 직후 등) 빈 목록.
        List<GradePriceRow> gradePrices = schedules.isEmpty()
                ? List.of()
                : seatMapper.selectGradePrices(schedules.get(0).getId());

        model.addAttribute("performance", performance);
        // 회차는 날짜별로 묶어서 넘긴다. 요일별 여러 시각이 잡힌 공연은 그냥 나열하면
        // 칩 수십 개가 한 덩어리로 쏟아져 어느 날 몇 시인지 읽어낼 수 없다.
        model.addAttribute("scheduleDays", ScheduleDayView.groupByDay(schedules));
        model.addAttribute("gradePrices", gradePrices);
        // 포스터 아래 sticky 요약(최저가 · 가장 빠른 회차).
        PerformanceSummaryView summary =
                PerformanceSummaryView.of(performance, schedules, gradePrices, LocalDateTime.now());
        model.addAttribute("summary", summary);
        // 공유 카드 + schema.org/Event. 요약과 같은 값(최저가·가장 빠른 회차)을 쓰므로
        // 화면에 보이는 것과 공유 카드에 뜨는 것이 어긋나지 않는다.
        model.addAttribute("seo", seoView.forPerformance(performance, summary, baseUrl()));
        // 지도 스크립트 URL. 키가 없으면 null 이고 템플릿이 링크로만 대체한다.
        model.addAttribute("naverMapScriptUrl", naverMapProperties.scriptUrl());
        return "performance/detail";
    }

    @GetMapping("/schedules/{scheduleId}/seats")
    public String seatMap(@PathVariable Long scheduleId, Model model) {
        PerformanceSchedule schedule = scheduleRepository.findWithPerformanceById(scheduleId)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다."));

        // 이미 시작된 회차의 좌석도는 고를 게 없는 화면이다. 404 로 두면 "없는 회차"라는
        // 틀린 설명이 되므로(존재는 한다) 공연 상세로 되돌려 남은 회차를 고르게 한다.
        // 예매 자체는 ReservationService 가 따로 막는다 — 여기 리다이렉트는 안내일 뿐이다.
        if (schedule.isPast(LocalDateTime.now())) {
            return "redirect:/performances/" + schedule.getPerformance().getId();
        }
        List<SeatMapRow> seats = seatMapper.selectSeatMap(scheduleId);
        Performance performance = schedule.getPerformance();

        model.addAttribute("schedule", schedule);
        model.addAttribute("performance", performance);
        // 무대 기준 배치 → 층 → 행 → 좌석으로 접어서 넘긴다. 템플릿에서 그룹핑하면 정렬이 깨진다.
        model.addAttribute("arena", seatMapView.arenaOf(
                seats, performance.getVenueHallId(), performance.getVenue()));
        return "reservation/seat-map";
    }
}
