package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.payment.TossPaymentClient;
import com.portfolio.ticket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 예매 핵심 로직.
 *
 * 좌석 선점(hold)을 4가지 전략으로 구현해 두고 동일한 부하 테스트로 비교한다.
 * 실제 서비스 경로는 PESSIMISTIC 이고, 나머지는 측정/설명용이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** 결제 대기 시간 */
    private static final int HOLD_MINUTES = 10;

    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ReservationRepository reservationRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final TossPaymentClient tossPaymentClient;

    /* ------------------------------------------------------------------
     *  1) 락 없음 - 오버부킹이 발생하는 원본 코드
     * ------------------------------------------------------------------ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdWithoutLock(Long seatId, Long memberId) {
        Seat seat = seatRepository.findByIdPlain(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));

        // 조회와 갱신 사이에 다른 트랜잭션이 끼어들 수 있다.
        if (!seat.isAvailable()) {
            throw new SeatAlreadyTakenException("이미 선점된 좌석입니다.");
        }
        seat.hold();
        return createReservation(seat, memberId);
    }

    /* ------------------------------------------------------------------
     *  2) 비관적 락 - 실제 서비스 경로
     * ------------------------------------------------------------------ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdWithPessimisticLock(Long seatId, Long memberId) {
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));

        if (!seat.isAvailable()) {
            throw new SeatAlreadyTakenException("이미 선점된 좌석입니다.");
        }
        seat.hold();
        return createReservation(seat, memberId);
    }

    /* ------------------------------------------------------------------
     *  3) 낙관적 락 - 커밋 시점에 version 충돌
     *     재시도는 ReservationFacade 가 담당 (트랜잭션 밖에서 돌아야 하므로)
     * ------------------------------------------------------------------ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdWithOptimisticLock(Long seatId, Long memberId) {
        Seat seat = seatRepository.findByIdPlain(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));

        if (!seat.isAvailable()) {
            throw new SeatAlreadyTakenException("이미 선점된 좌석입니다.");
        }
        seat.hold();
        return createReservation(seat, memberId);
    }

    /* ------------------------------------------------------------------
     *  4) 유니크 제약 - seat_hold PK 충돌로 경쟁을 DB 에 위임
     * ------------------------------------------------------------------ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdWithUniqueConstraint(Long seatId, Long memberId) {
        Seat seat = seatRepository.findByIdPlain(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));

        try {
            seatHoldRepository.saveAndFlush(SeatHold.builder()
                    .seatId(seatId)
                    .memberId(memberId)
                    .expiresAt(LocalDateTime.now().plusMinutes(HOLD_MINUTES))
                    .build());
        } catch (DataIntegrityViolationException e) {
            // PK 중복 = 다른 사용자가 먼저 선점
            throw new SeatAlreadyTakenException("이미 선점된 좌석입니다.");
        }

        if (!seat.isAvailable()) {
            throw new SeatAlreadyTakenException("이미 선점된 좌석입니다.");
        }
        seat.hold();
        return createReservation(seat, memberId);
    }

    /* ------------------------------------------------------------------
     *  결제 확정 / 취소
     * ------------------------------------------------------------------ */
    /** 토스 결제 승인이 끝난 뒤 호출한다. paymentKey 는 이후 취소/환불 API 호출에 쓰인다. */
    @Transactional
    public Reservation confirmPayment(String reservationNo, String paymentKey) {
        Reservation reservation = reservationRepository.findByReservationNo(reservationNo)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역 없음. no=" + reservationNo));

        if (reservation.isHoldExpired(LocalDateTime.now())) {
            throw new IllegalStateException("결제 가능 시간이 지났습니다.");
        }
        reservation.confirm(paymentKey);
        reservation.getSeat().sell();
        seatHoldRepository.deleteById(reservation.getSeat().getId());
        return reservation;
    }

    @Transactional
    public int cancel(String reservationNo, Long memberId) {
        Reservation reservation = reservationRepository.findByReservationNo(reservationNo)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역 없음. no=" + reservationNo));

        if (!reservation.getMemberId().equals(memberId)) {
            throw new IllegalStateException("본인 예매만 취소할 수 있습니다.");
        }

        Seat seat = seatRepository.findByIdForUpdate(reservation.getSeat().getId())
                .orElseThrow(() -> new IllegalStateException("좌석 없음"));

        LocalDateTime showAt = seat.getSchedule().getShowAt();
        int feeRate = reservation.refundFeeRate(showAt, LocalDateTime.now());
        int refund = reservation.getAmount() * (100 - feeRate) / 100;

        // 결제가 이미 승인된 건(paymentKey 존재)만 토스에 실제 환불을 요청한다.
        // 아직 결제 전(PENDING, 선점만 된 상태)이면 환불할 돈이 없으므로 스킵.
        if (reservation.getPaymentKey() != null) {
            tossPaymentClient.cancel(reservation.getPaymentKey(), "고객 요청 취소", refund);
        }

        reservation.cancel();
        seat.release();
        seatHoldRepository.deleteById(seat.getId());

        PerformanceSchedule schedule = scheduleRepository.findByIdForUpdate(seat.getSchedule().getId())
                .orElseThrow(() -> new IllegalStateException("회차 없음"));
        schedule.increaseRemaining();

        log.info("예매 취소. no={}, feeRate={}%, refund={}", reservationNo, feeRate, refund);
        return refund;
    }

    /* ------------------------------------------------------------------
     *  내부 공통
     * ------------------------------------------------------------------ */
    private Reservation createReservation(Seat seat, Long memberId) {
        PerformanceSchedule schedule = scheduleRepository.findByIdForUpdate(seat.getSchedule().getId())
                .orElseThrow(() -> new IllegalStateException("회차 없음"));
        schedule.decreaseRemaining();

        LocalDateTime now = LocalDateTime.now();
        return reservationRepository.save(Reservation.builder()
                .reservationNo(generateReservationNo())
                .memberId(memberId)
                .seat(seat)
                .status(ReservationStatus.PENDING)
                .amount(seat.getPrice())
                .createdAt(now)
                .holdExpiresAt(now.plusMinutes(HOLD_MINUTES))
                .build());
    }

    /** yyMMdd + 8자리 난수 */
    private String generateReservationNo() {
        String date = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        int rand = ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        return date + rand;
    }

    @Transactional(readOnly = true)
    public List<Reservation> findMyReservations(Long memberId) {
        return reservationRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }
}
