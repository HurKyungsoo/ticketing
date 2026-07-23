package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SeatMapRow {
    private Long seatId;
    private String section;
    private int seatNo;
    private String grade;
    private String status;
    private int price;
}
