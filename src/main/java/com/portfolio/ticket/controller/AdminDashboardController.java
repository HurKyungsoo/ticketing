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
import java.util.List;
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
        DateTimeFormatter dateLabel = DateTimeFormatter.ofPattern("M/d");
        model.addAttribute("dailyLabels",
                dailySales.stream().map(row -> row.getSaleDate().format(dateLabel)).collect(Collectors.toList()));
        model.addAttribute("dailyAmounts",
                dailySales.stream().map(SalesStatRow::getTotalAmount).collect(Collectors.toList()));

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
}
