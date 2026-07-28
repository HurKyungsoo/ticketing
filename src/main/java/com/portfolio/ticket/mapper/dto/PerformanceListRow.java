package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

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
}
