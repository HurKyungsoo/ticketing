package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CancelStatsRow {
    private long confirmedCount;
    private long canceledCount;
}
