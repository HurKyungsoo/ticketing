package com.portfolio.ticket.mapper.dto;

import com.portfolio.ticket.domain.Seat;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SeatMapRow {
    private Long seatId;
    private String section;
    private int rowNo;
    private int seatNo;
    /** 이 좌석 오른쪽에 통로가 있는지. 좌석도가 아는 사실이라 화면에서 추정하지 않는다. */
    private boolean aisleAfter;
    private String grade;
    private String status;
    private int price;

    /** "1층 3열 12번". 엔티티와 같은 표기를 쓰려고 Seat 의 규칙을 그대로 빌린다. */
    public String getLabel() {
        return Seat.labelOf(section, rowNo, seatNo);
    }
}
