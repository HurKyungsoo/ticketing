package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "reservation",
    indexes = {
        @Index(name = "idx_reservation_member", columnList = "memberId"),
        @Index(name = "idx_reservation_seat", columnList = "seat_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Reservation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String reservationNo;

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 결제 마감 시각 (선점 만료) */
    @Column(nullable = false)
    private LocalDateTime holdExpiresAt;

    private LocalDateTime confirmedAt;
    private LocalDateTime canceledAt;

    /** 토스페이먼츠 결제 건 식별자. 결제 승인 후에만 값이 채워진다 (취소/환불 API 호출에 필요). */
    private String paymentKey;

    public void confirm(String paymentKey) {
        if (status != ReservationStatus.PENDING) {
            throw new IllegalStateException("결제 대기 상태가 아닙니다. status=" + status);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
        this.paymentKey = paymentKey;
    }

    public void cancel() {
        if (status == ReservationStatus.CANCELED) return;
        this.status = ReservationStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
    }

    public void expire() {
        this.status = ReservationStatus.EXPIRED;
    }

    public boolean isHoldExpired(LocalDateTime now) {
        return status == ReservationStatus.PENDING && holdExpiresAt.isBefore(now);
    }

    /**
     * 관람일 기준 환불 수수료율.
     * 10일 전 0% / 7일 전 10% / 3일 전 20% / 1일 전 30% / 당일 취소 불가
     */
    public int refundFeeRate(LocalDateTime showAt, LocalDateTime now) {
        long days = Duration.between(now, showAt).toDays();
        if (days >= 10) return 0;
        if (days >= 7) return 10;
        if (days >= 3) return 20;
        if (days >= 1) return 30;
        throw new IllegalStateException("공연 당일에는 취소할 수 없습니다.");
    }
}
