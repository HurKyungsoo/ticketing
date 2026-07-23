package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 좌석. 동시성 실험의 주인공.
 * - 비관적 락: SeatRepository.findByIdForUpdate()
 * - 낙관적 락: @Version 필드
 * - 유니크 제약: SeatHold 테이블의 PK
 */
@Entity
@Table(
    name = "seat",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_seat", columnNames = {"schedule_id", "section", "seatNo"}),
    indexes = @Index(name = "idx_seat_schedule_status", columnList = "schedule_id, status")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Seat {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private PerformanceSchedule schedule;

    @Column(nullable = false, length = 5)
    private String section;

    @Column(nullable = false)
    private int seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(nullable = false)
    private int price;

    @Version
    private Long version;

    public boolean isAvailable() {
        return this.status == SeatStatus.AVAILABLE;
    }

    public void hold() {
        if (!isAvailable()) {
            throw new IllegalStateException("이미 선점되었거나 판매된 좌석입니다. seatId=" + id);
        }
        this.status = SeatStatus.HELD;
    }

    public void sell() {
        if (this.status != SeatStatus.HELD) {
            throw new IllegalStateException("선점 상태가 아닌 좌석은 판매할 수 없습니다. seatId=" + id);
        }
        this.status = SeatStatus.SOLD;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
    }

    public String seatLabel() {
        return section + "-" + seatNo;
    }
}
