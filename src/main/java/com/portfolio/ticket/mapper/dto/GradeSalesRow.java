package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GradeSalesRow {
    private String grade;
    private long ticketCount;
    private long totalAmount;
}
