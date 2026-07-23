package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 회차. 잔여석 카운터를 들고 있고 @Version 으로 갱신 손실을 막는다.
 */
@Entity
@Table(
    name = "performance_schedule",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_schedule", columnNames = {"performance_id", "showAt"}),
    indexes = @Index(name = "idx_schedule_showat", columnList = "showAt")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PerformanceSchedule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id")
    private Performance performance;

    @Column(nullable = false)
    private LocalDateTime showAt;

    @Column(nullable = false)
    private int totalSeats;

    @Column(nullable = false)
    private int remainingSeats;

    @Version
    private Long version;

    void assignPerformance(Performance performance) {
        this.performance = performance;
    }

    public void decreaseRemaining() {
        if (remainingSeats <= 0) {
            throw new IllegalStateException("잔여 좌석이 없습니다.");
        }
        this.remainingSeats--;
    }

    public void increaseRemaining() {
        if (remainingSeats < totalSeats) {
            this.remainingSeats++;
        }
    }
}
