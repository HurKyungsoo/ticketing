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

    /**
     * 실제 좌석 생성 결과로 총석수/잔여석을 맞춘다.
     * 공연 API 의 객석수 추정치와 VenueLayout 이 실제로 만든 좌석 수가 다를 때 쓴다.
     * 예매가 시작되기 전(좌석 생성 직후)에만 호출해야 한다.
     */
    public void syncActualSeatCount(int actual) {
        this.totalSeats = actual;
        this.remainingSeats = actual;
    }

    /**
     * 이미 시작된(지난) 회차인지.
     *
     * <p>공연 기간이 여러 날인 공연은 앞쪽 회차가 지나 있는 게 정상이다. 그 회차들을 걸러내지
     * 않으면 <b>이미 끝난 공연을 예매할 수 있다</b> — 실제로 화면에도 목록에 그대로 떴고,
     * 좌석 선점부터 결제까지 어디에도 시각 검증이 없었다.
     *
     * <p>기준은 날짜가 아니라 시각이다. 오늘 09시 공연은 지났고 오늘 19시 공연은 아직 남았다.
     */
    public boolean isPast(LocalDateTime now) {
        return !showAt.isAfter(now);
    }
}
