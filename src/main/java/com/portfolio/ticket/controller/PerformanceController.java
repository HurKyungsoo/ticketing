package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.SeatMapRow;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatMapper seatMapper;

    @GetMapping("/")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<Performance> performances = performanceRepository
                .findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.now(), PageRequest.of(page, 12));
        model.addAttribute("performances", performances);
        return "performance/list";
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
