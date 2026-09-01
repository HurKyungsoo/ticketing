package com.portfolio.ticket.controller;

import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.CancelStatsRow;
import com.portfolio.ticket.mapper.dto.GradeSalesRow;
import com.portfolio.ticket.mapper.dto.PerformanceSalesRow;
import com.portfolio.ticket.mapper.dto.SalesStatRow;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 대시보드. 집계는 전부 SeatMapper(MyBatis) 로 처리한다.
 * 접근 제어는 SecurityConfig 에서 "/admin/**" 를 ROLE_ADMIN 으로 제한한다.
 */
@Controller
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    /** 기본 조회 기간: 최근 14일 (오늘 포함) */
    private static final int DEFAULT_RANGE_DAYS = 13;
    private static final int TOP_PERFORMANCE_LIMIT = 5;
    /** 조회 기간이 이보다 길면(약 10년) 빈 날짜를 채우지 않는다 — 날짜 입력 오조작으로
        극단적인 범위가 들어와도 차트 배열이 무한정 커지지 않게 하는 안전장치다. */
    private static final long MAX_FILLABLE_RANGE_DAYS = 3650;

    private final SeatMapper seatMapper;

    @GetMapping
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             Model model) {
        LocalDate rangeTo = to != null ? to : LocalDate.now();
        LocalDate rangeFrom = from != null ? from : rangeTo.minusDays(DEFAULT_RANGE_DAYS);

        List<SalesStatRow> dailySales = seatMapper.selectDailySales(rangeFrom, rangeTo, null);
        List<PerformanceSalesRow> topPerformances =
                seatMapper.selectTopPerformances(rangeFrom, rangeTo, TOP_PERFORMANCE_LIMIT);
        List<GradeSalesRow> gradeSales = seatMapper.selectSalesByGrade(rangeFrom, rangeTo, null);
        CancelStatsRow cancelStats = seatMapper.selectCancelStats(rangeFrom, rangeTo, null);

        // 요약 카드 4개 중 3개(총 매출/총 건수/객단가)는 selectDailySales 결과를 그대로 합산한 것이다.
        long totalAmount = dailySales.stream().mapToLong(SalesStatRow::getTotalAmount).sum();
        long totalTicketCount = dailySales.stream().mapToLong(SalesStatRow::getTicketCount).sum();
        long avgOrderAmount = totalTicketCount == 0 ? 0 : totalAmount / totalTicketCount;

        long confirmedCount = cancelStats != null ? cancelStats.getConfirmedCount() : 0;
        long canceledCount = cancelStats != null ? cancelStats.getCanceledCount() : 0;
        long totalAttempts = confirmedCount + canceledCount;
        double cancelRate = totalAttempts == 0 ? 0.0 : (canceledCount * 100.0) / totalAttempts;

        model.addAttribute("from", rangeFrom);
        model.addAttribute("to", rangeTo);
        model.addAttribute("totalAmount", totalAmount);
        model.addAttribute("totalTicketCount", totalTicketCount);
        model.addAttribute("avgOrderAmount", avgOrderAmount);
        model.addAttribute("cancelRate", cancelRate);
        model.addAttribute("dailySales", dailySales);
        model.addAttribute("topPerformances", topPerformances);
        model.addAttribute("gradeSales", gradeSales);

        // 차트는 Chart.js 에 바로 넘길 수 있게 라벨/값 배열로 미리 뽑아둔다
        // (템플릿에서 SpringEL projection 을 쓰는 대신 여기서 정리하는 게 안전하다).
        //
        // dailySales 는 그대로 두고(요약 카드 합산·"결제 확정된 예매가 없습니다" 빈 화면
        // 판단에 쓰인다) 차트용으로만 빈 날짜를 채운 별도 목록을 만든다 — selectDailySales
        // 가 GROUP BY 라 매출 0인 날은 행 자체가 없어서, 그대로 넘기면 "추이" 그래프에서
        // 매출 없는 날이 통째로 빠져 x축 간격이 들쭉날쭉해지고(예: 8/19~9/1을 조회했는데
        // 점이 9/1 하루뿐) y축도 실제 나온 매출값 범위로만 좁게 잡혀 정작 추이가 안 보였다.
        DateTimeFormatter dateLabel = DateTimeFormatter.ofPattern("M/d");
        List<SalesStatRow> dailySalesForChart = fillDailySalesGaps(dailySales, rangeFrom, rangeTo);
        model.addAttribute("dailyLabels",
                dailySalesForChart.stream().map(row -> row.getSaleDate().format(dateLabel)).collect(Collectors.toList()));
        model.addAttribute("dailyAmounts",
                dailySalesForChart.stream().map(SalesStatRow::getTotalAmount).collect(Collectors.toList()));

        model.addAttribute("topLabels",
                topPerformances.stream().map(PerformanceSalesRow::getTitle).collect(Collectors.toList()));
        model.addAttribute("topAmounts",
                topPerformances.stream().map(PerformanceSalesRow::getTotalAmount).collect(Collectors.toList()));

        model.addAttribute("gradeLabels",
                gradeSales.stream().map(GradeSalesRow::getGrade).collect(Collectors.toList()));
        model.addAttribute("gradeAmounts",
                gradeSales.stream().map(GradeSalesRow::getTotalAmount).collect(Collectors.toList()));

        return "admin/dashboard";
    }

    /**
     * sparse(매출 있는 날만) 를 from~to 매일 하루씩 순회하며 빈 날을 0원으로 채운 목록으로
     * 바꾼다. from/to 순서가 뒤바뀌었거나(빈 결과가 이미 맞는 답이라 채울 게 없다) 범위가
     * {@link #MAX_FILLABLE_RANGE_DAYS} 를 넘으면 원본을 그대로 돌려준다 — 조회 기간 자체가
     * 비정상인 경우까지 이 메서드가 책임질 일은 아니다.
     */
    private List<SalesStatRow> fillDailySalesGaps(List<SalesStatRow> sparse, LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_FILLABLE_RANGE_DAYS) {
            return sparse;
        }

        Map<LocalDate, Long> amountByDate = sparse.stream()
                .collect(Collectors.toMap(SalesStatRow::getSaleDate, SalesStatRow::getTotalAmount));

        List<SalesStatRow> filled = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            SalesStatRow row = new SalesStatRow();
            row.setSaleDate(date);
            row.setTotalAmount(amountByDate.getOrDefault(date, 0L));
            filled.add(row);
        }
        return filled;
    }
}
