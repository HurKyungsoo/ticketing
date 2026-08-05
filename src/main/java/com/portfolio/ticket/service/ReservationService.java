package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.external.ExternalInventoryClient;
import com.portfolio.ticket.payment.TossPaymentClient;
import com.portfolio.ticket.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

    /** 1인당 매수 제한 (같은 회차 기준, 결제대기 + 확정 합산). 국내 티켓 플랫폼 표준 관례. */
    private static final int MAX_SEATS_PER_MEMBER_PER_SCHEDULE = 4;

    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ReservationRepository reservationRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final TossPaymentClient tossPaymentClient;
    private final ExternalInventoryClient externalInventoryClient;

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
     *  다중 좌석 선점 - 위 4가지 전략을 좌석 여러 개에 그대로 적용한다.
     *  좌석은 항상 id 오름차순으로 잠근다. 동시에 들어온 두 다중선점 요청이
     *  겹치는 좌석을 서로 다른 순서로 잠그면 데드락이 나므로, 기존
     *  "Seat -> PerformanceSchedule" 순서 규칙에 "Seat 여러 개 사이의 순서"도
     *  추가된 셈이다. 하나라도 실패하면 PartialSeatHoldException 을 던져
     *  트랜잭션 전체를 롤백한다(부분 성공 없음).
     * ------------------------------------------------------------------ */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdMultipleWithoutLock(List<Long> seatIds, Long memberId) {
        List<Seat> seats = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long seatId : sortedIds(seatIds)) {
            Seat seat = seatRepository.findByIdPlain(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));
            if (!seat.isAvailable()) {
                failed.add(seatId);
                continue;
            }
            seat.hold();
            seats.add(seat);
        }
        if (!failed.isEmpty()) {
            throw new PartialSeatHoldException("이미 선점된 좌석이 있습니다.", failed);
        }
        return createReservation(seats, memberId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdMultipleWithPessimisticLock(List<Long> seatIds, Long memberId) {
        List<Seat> seats = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long seatId : sortedIds(seatIds)) {
            Seat seat = seatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));
            if (!seat.isAvailable()) {
                failed.add(seatId);
                continue;
            }
            seat.hold();
            seats.add(seat);
        }
        if (!failed.isEmpty()) {
            throw new PartialSeatHoldException("이미 선점된 좌석이 있습니다.", failed);
        }
        return createReservation(seats, memberId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdMultipleWithOptimisticLock(List<Long> seatIds, Long memberId) {
        List<Seat> seats = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long seatId : sortedIds(seatIds)) {
            Seat seat = seatRepository.findByIdPlain(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));
            if (!seat.isAvailable()) {
                failed.add(seatId);
                continue;
            }
            seat.hold();
            seats.add(seat);
        }
        if (!failed.isEmpty()) {
            throw new PartialSeatHoldException("이미 선점된 좌석이 있습니다.", failed);
        }
        return createReservation(seats, memberId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation holdMultipleWithUniqueConstraint(List<Long> seatIds, Long memberId) {
        List<Seat> seats = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long seatId : sortedIds(seatIds)) {
            Seat seat = seatRepository.findByIdPlain(seatId)
                    .orElseThrow(() -> new IllegalArgumentException("좌석 없음. id=" + seatId));
            try {
                seatHoldRepository.saveAndFlush(SeatHold.builder()
                        .seatId(seatId)
                        .memberId(memberId)
                        .expiresAt(LocalDateTime.now().plusMinutes(HOLD_MINUTES))
                        .build());
            } catch (DataIntegrityViolationException e) {
                failed.add(seatId);
                continue;
            }
            if (!seat.isAvailable()) {
                failed.add(seatId);
                continue;
            }
            seat.hold();
            seats.add(seat);
        }
        if (!failed.isEmpty()) {
            throw new PartialSeatHoldException("이미 선점된 좌석이 있습니다.", failed);
        }
        return createReservation(seats, memberId);
    }

    private List<Long> sortedIds(List<Long> seatIds) {
        return seatIds.stream().distinct().sorted().toList();
    }

    /* ------------------------------------------------------------------
     *  결제 확정 / 취소
     * ------------------------------------------------------------------ */
    /** 토스 결제 승인이 끝난 뒤 호출한다. paymentKey 는 이후 취소/환불 API 호출에 쓰인다. */
    @Transactional
    public Reservation confirmPayment(String reservationNo, String paymentKey) {
        Reservation reservation = reservationRepository.findByReservationNo(reservationNo)
                .orElseThrow(() -> new NotFoundException("예매 내역 없음. no=" + reservationNo));

        // 락 순서: Seat(오름차순) -> PerformanceSchedule (CLAUDE.md 동시성 규칙).
        // HoldExpireScheduler 가 같은 좌석을 만료 처리 중일 수 있어 좌석 행을 먼저 잠가
        // "결제는 승인됐는데 좌석은 이미 풀렸다" 경쟁을 막는다.
        List<Seat> seats = lockSeatsInOrder(reservation);

        if (reservation.isHoldExpired(LocalDateTime.now())) {
            throw new IllegalStateException("결제 가능 시간이 지났습니다.");
        }
        reservation.confirm(paymentKey);
        for (Seat seat : seats) {
            seat.sell();
            seatHoldRepository.deleteById(seat.getId());
        }
        externalInventoryClient.notifySold(seats.get(0).getSchedule().getId(), seats.size());
        return reservation;
    }

    @Transactional
    public int cancel(String reservationNo, Long memberId) {
        Reservation reservation = reservationRepository.findByReservationNo(reservationNo)
                .orElseThrow(() -> new NotFoundException("예매 내역을 찾을 수 없습니다."));

        if (!reservation.getMemberId().equals(memberId)) {
            throw new ForbiddenException("본인 예매만 취소할 수 있습니다.");
        }

        // 이미 취소/만료된 건은 좌석 연결이 이미 끊겨 있다(Seat.release()). 그대로 진행하면
        // 잠글 좌석이 하나도 없어 seats.get(0) 에서 터진다 — 취소 버튼을 두 번 누르거나
        // 만료된 화면에서 취소하면 500 이 났다. 풀어줄 좌석도 환불할 돈도 없으니 여기서 끝낸다.
        if (reservation.getStatus() == ReservationStatus.CANCELED
                || reservation.getStatus() == ReservationStatus.EXPIRED) {
            log.debug("이미 종료된 예매라 취소를 건너뛴다. no={}, status={}",
                    reservationNo, reservation.getStatus());
            return 0;
        }

        List<Seat> seats = lockSeatsInOrder(reservation);

        // 회차는 예매가 직접 들고 있다. 좌석에서 꺼내면 위처럼 좌석이 없는 순간 못 쓴다.
        LocalDateTime showAt = reservation.getSchedule().getShowAt();
        int feeRate = reservation.refundFeeRate(showAt, LocalDateTime.now());
        int refund = reservation.getAmount() * (100 - feeRate) / 100;

        // 결제가 이미 승인된 건(paymentKey 존재)만 토스에 실제 환불을 요청한다.
        // 아직 결제 전(PENDING, 선점만 된 상태)이면 환불할 돈이 없으므로 스킵.
        if (reservation.getPaymentKey() != null) {
            tossPaymentClient.cancel(reservation.getPaymentKey(), "고객 요청 취소", refund);
        }

        reservation.cancel();
        for (Seat seat : seats) {
            seat.release();
            seatHoldRepository.deleteById(seat.getId());
        }

        // 락 순서는 Seat -> PerformanceSchedule 유지 (좌석은 위에서 이미 잠갔다).
        PerformanceSchedule schedule = scheduleRepository.findByIdForUpdate(reservation.getSchedule().getId())
                .orElseThrow(() -> new IllegalStateException("회차 없음"));
        for (int i = 0; i < seats.size(); i++) {
            schedule.increaseRemaining();
        }
        externalInventoryClient.notifyReleased(schedule.getId(), seats.size());

        log.info("예매 취소. no={}, feeRate={}%, refund={}", reservationNo, feeRate, refund);
        return refund;
    }

    /**
     * 마이페이지 "삭제" — 행을 지우지 않고 이 회원의 목록 조회에서만 뺀다(엔티티
     * {@link Reservation#hide()} 참고). 취소·만료된 건만 대상이라 좌석·회차를 잠글 일이
     * 없다(이미 {@link #cancel} 또는 만료 스케줄러가 좌석 연결을 끊어 둔 뒤다).
     */
    @Transactional
    public void hide(String reservationNo, Long memberId) {
        Reservation reservation = reservationRepository.findByReservationNo(reservationNo)
                .orElseThrow(() -> new NotFoundException("예매 내역을 찾을 수 없습니다."));

        if (!reservation.getMemberId().equals(memberId)) {
            throw new ForbiddenException("본인 예매만 삭제할 수 있습니다.");
        }

        reservation.hide();
    }

    /** 예매에 속한 좌석들을 id 오름차순으로 하나씩 잠근다 (데드락 방지 순서 고정). */
    private List<Seat> lockSeatsInOrder(Reservation reservation) {
        List<Long> sortedSeatIds = reservation.getSeats().stream().map(Seat::getId).sorted().toList();
        List<Seat> seats = new ArrayList<>();
        for (Long seatId : sortedSeatIds) {
            seats.add(seatRepository.findByIdForUpdate(seatId)
                    .orElseThrow(() -> new IllegalStateException("좌석 없음")));
        }
        return seats;
    }

    /* ------------------------------------------------------------------
     *  내부 공통
     * ------------------------------------------------------------------ */
    private Reservation createReservation(Seat seat, Long memberId) {
        return createReservation(List.of(seat), memberId);
    }

    private Reservation createReservation(List<Seat> seats, Long memberId) {
        Long scheduleId = seats.get(0).getSchedule().getId();
        boolean sameSchedule = seats.stream().allMatch(s -> s.getSchedule().getId().equals(scheduleId));
        if (!sameSchedule) {
            throw new IllegalArgumentException("서로 다른 회차의 좌석을 한 번에 예매할 수 없습니다.");
        }

        PerformanceSchedule schedule = scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new IllegalStateException("회차 없음"));

        LocalDateTime now = LocalDateTime.now();
        // 이미 시작된 공연은 예매할 수 없다. 목록에서 지난 회차를 감추는 것만으로는 부족하다 —
        // 좌석도 URL(/schedules/{id}/seats)과 선점 API 는 회차 id 만 있으면 그대로 호출되므로,
        // 두 hold 경로가 모두 지나가는 이 지점에서 막아야 한다.
        if (schedule.isPast(now)) {
            throw new IllegalStateException("이미 시작된 공연은 예매할 수 없습니다.");
        }

        // 매수 제한 체크. 위에서 잡은 schedule 락(findByIdForUpdate)이 이 회차에 대한 모든
        // createReservation 호출을 이미 직렬화하고 있으므로, 같은 회원이 동시에 여러 요청을
        // 보내도(다른 좌석이라 Seat 락으로는 안 걸러진다) 여기서는 경쟁 없이 정확한 카운트를 본다.
        long alreadyHeld = seatRepository.countByScheduleIdAndReservationMemberIdAndStatusIn(
                scheduleId, memberId, List.of(SeatStatus.HELD, SeatStatus.SOLD));
        if (alreadyHeld + seats.size() > MAX_SEATS_PER_MEMBER_PER_SCHEDULE) {
            throw new PurchaseLimitExceededException(
                    "1인당 최대 " + MAX_SEATS_PER_MEMBER_PER_SCHEDULE + "매까지 예매할 수 있습니다.");
        }

        for (int i = 0; i < seats.size(); i++) {
            schedule.decreaseRemaining();
        }

        Reservation reservation = reservationRepository.save(Reservation.builder()
                .reservationNo(generateReservationNo())
                .memberId(memberId)
                .schedule(schedule)
                .seatSummary(summarizeSeats(seats))
                .status(ReservationStatus.PENDING)
                .amount(seats.stream().mapToInt(Seat::getPrice).sum())
                .createdAt(now)
                .holdExpiresAt(now.plusMinutes(HOLD_MINUTES))
                .build());
        seats.forEach(seat -> seat.assignReservation(reservation));
        return reservation;
    }

    /** 좌석 표기를 예매에 스냅샷으로 남긴다. 취소되면 좌석 연결이 끊겨 되짚을 수 없기 때문이다. */
    private String summarizeSeats(List<Seat> seats) {
        return seats.stream()
                .map(seat -> seat.seatLabel() + " " + seat.getGrade())
                .collect(Collectors.joining(", "));
    }

    /** yyMMdd + 8자리 난수 */
    private String generateReservationNo() {
        String date = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
        int rand = ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        return date + rand;
    }

    @Transactional(readOnly = true)
    public List<Reservation> findMyReservations(Long memberId) {
        return reservationRepository.findWithScheduleByMemberIdOrderByCreatedAtDesc(memberId);
    }
}
