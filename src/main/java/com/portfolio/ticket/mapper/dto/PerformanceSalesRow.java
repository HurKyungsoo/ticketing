package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PerformanceSalesRow {
    private Long performanceId;
    private String title;
    private long ticketCount;
    private long totalAmount;
}
