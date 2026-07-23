package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class SalesStatRow {
    private LocalDate saleDate;
    private long ticketCount;
    private long totalAmount;
}
