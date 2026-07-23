package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 좌석 선점 티켓. seatId 가 곧 PK 이므로 DB 가 중복 선점을 막아준다.
 * 락을 전혀 걸지 않아도 INSERT 하나로 경쟁이 정리되는 방식.
 */
@Entity
@Table(name = "seat_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SeatHold {

    @Id
    @Column(name = "seat_id")
    private Long seatId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
