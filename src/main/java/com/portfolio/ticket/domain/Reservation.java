package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "reservation",
    indexes = {
        @Index(name = "idx_reservation_member", columnList = "memberId")
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

    /** FK 는 Seat 쪽에 있다 (seat.reservation_id) — 예매 1건이 좌석 여러 개를 가질 수 있어서. */
    @OneToMany(mappedBy = "reservation", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Seat> seats = new ArrayList<>();

    /**
     * 이 예매의 회차. 좌석을 통해서도 갈 수 있지만 그것만으로는 부족하다 —
     * 취소/만료되면 {@link Seat#release()} 가 좌석 연결을 끊어서 공연명·일시를 잃는다.
     * 예매는 지난 내역으로 남아야 하므로 회차를 직접 들고 있는다.
     *
     * <p>한 예매의 좌석은 모두 같은 회차다 (ReservationService.createReservation 에서 강제).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private PerformanceSchedule schedule;

    /**
     * 예매 시점의 좌석 표기 스냅샷("1층 1열 1번 VIP, 3층 5열 1번 A").
     *
     * <p>취소하면 좌석 연결이 끊기고 그 좌석은 다시 팔리므로, 어떤 좌석이었는지는 살아 있는
     * seat 행에서 되짚을 수 없다. 예매는 영수증 성격이라 그 시점 값을 그대로 남긴다.
     */
    @Column(length = 500)
    private String seatSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    /** 이 예매에 속한 좌석 전체 가격 합계. */
    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 결제 마감 시각 (선점 만료) */
    @Column(nullable = false)
    private LocalDateTime holdExpiresAt;

    private LocalDateTime confirmedAt;
    private LocalDateTime canceledAt;

    /**
     * 마이페이지 "삭제". 행을 실제로 지우지 않는다 — 예매는 영수증 성격이라 취소·환불
     * 이력, 매출 집계에서 계속 살아 있어야 한다(REFUND_TIERS 주석·ReservationRepository
     * 참고). 이 사용자의 목록 조회에서만 뺀다.
     */
    private LocalDateTime hiddenAt;

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

    /**
     * 취소·만료된 예매만 삭제(숨김)할 수 있다. 결제 대기·확정 건은 아직 유효한 표라 목록에서
     * 빠지면 안 된다 — 특히 확정 건이 사라지면 관람일에 뭘 예매했는지 볼 방법이 없어진다.
     */
    public void hide() {
        if (status != ReservationStatus.CANCELED && status != ReservationStatus.EXPIRED) {
            throw new IllegalStateException("취소되었거나 만료된 예매만 삭제할 수 있습니다. status=" + status);
        }
        this.hiddenAt = LocalDateTime.now();
    }

    public boolean isHoldExpired(LocalDateTime now) {
        return status == ReservationStatus.PENDING && holdExpiresAt.isBefore(now);
    }

    /**
     * 환불 수수료 한 단계. {@code minDaysBefore} 일 이상 남았으면 {@code feeRate}% 를 뗀다.
     *
     * <p>공연 상세의 "취소·환불 규정" 안내가 이 목록을 그대로 읽어서 그린다. 규정을 화면에
     * 문자열로 다시 적으면 요율을 고칠 때 한쪽만 바뀌어 조용히 어긋난다 — 사용자에게 고지한
     * 것과 실제로 청구되는 금액이 달라지는 건 단순 표기 오류가 아니다.
     */
    public record RefundTier(int minDaysBefore, int feeRate) {}

    /** 남은 일수가 많은 순. {@link #refundFeeRate} 가 위에서부터 훑으므로 순서가 규칙의 일부다. */
    public static final List<RefundTier> REFUND_TIERS = List.of(
            new RefundTier(10, 0),
            new RefundTier(7, 10),
            new RefundTier(3, 20),
            new RefundTier(1, 30)
    );

    /**
     * 관람일 기준 환불 수수료율.
     * 10일 전 0% / 7일 전 10% / 3일 전 20% / 1일 전 30% / 당일 취소 불가
     */
    public int refundFeeRate(LocalDateTime showAt, LocalDateTime now) {
        long days = Duration.between(now, showAt).toDays();
        for (RefundTier tier : REFUND_TIERS) {
            if (days >= tier.minDaysBefore()) return tier.feeRate();
        }
        throw new IllegalStateException("공연 당일에는 취소할 수 없습니다.");
    }

    /**
     * 마이페이지에서 "취소" 버튼을 누르기 전에 보여줄 안내. {@code feeRate}/{@code refundAmount} 는
     * 지금 취소하면 실제로 청구·환불될 값이고, {@code cancelable} 이 false 면(공연 당일) 그
     * 값들은 의미가 없다 — 화면은 그때 수수료율 대신 "취소할 수 없습니다" 를 보여줘야 한다.
     *
     * @param feeRate 결제 전(PENDING)이면 항상 0이다 — 취소 서비스가 결제 안 된 건은 토스
     *                환불 호출 자체를 건너뛰므로 수수료 개념이 없다.
     */
    public record RefundPreview(boolean cancelable, int feeRate, int refundAmount) {}

    /**
     * {@link #refundFeeRate} 를 화면에서 안전하게 쓰기 위한 래퍼. 그 메서드는 당일이면
     * 예외를 던지는데, Thymeleaf 에서 메서드 호출이 예외를 던지면 페이지 전체가 500 이 된다 —
     * 마이페이지처럼 여러 예매가 한 화면에 나열될 때 그중 하나가 당일이라고 화면 전체가
     * 죽으면 안 된다. 여기서 잡아서 {@code cancelable=false} 로 돌려준다.
     *
     * <p>확정(CONFIRMED) 건에만 실제 수수료를 계산한다 — 결제 대기 건은 취소해도 토스 환불을
     * 안 부르므로(아직 결제 전이라 환불할 돈이 없다) 항상 전액(수수료 0%) 취소다.
     */
    public RefundPreview refundPreview(LocalDateTime now) {
        if (status != ReservationStatus.CONFIRMED) {
            return new RefundPreview(true, 0, amount);
        }
        try {
            int feeRate = refundFeeRate(schedule.getShowAt(), now);
            return new RefundPreview(true, feeRate, amount * (100 - feeRate) / 100);
        } catch (IllegalStateException e) {
            return new RefundPreview(false, 0, 0);
        }
    }
}
