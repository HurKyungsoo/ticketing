package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 공연 하나의 "다음 회차" 한 줄. 찜 목록처럼 {@link PerformanceListRow} 를 안 쓰는 화면이
 * 공연 여러 건의 다음 회차를 한 번에 받아갈 때 쓴다.
 */
@Getter
@Setter
public class NextShowRow {
    private Long performanceId;
    private LocalDateTime nextShowAt;
}
