package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatStatus;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 안 하고 방치된 선점 좌석을 되돌리는 배치. 30초마다 동작.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpireScheduler {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final PerformanceScheduleRepository scheduleRepository;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now();

        List<Reservation> expired =
                reservationRepository.findByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, now);

        for (Reservation reservation : expired) {
            Long seatId = reservation.getSeat().getId();

            // 락 순서: Seat -> PerformanceSchedule (CLAUDE.md 동시성 규칙).
            // 결제 확정(ReservationService.confirmPayment)도 같은 좌석을 잠그므로,
            // 좌석이 이미 SOLD 로 넘어갔다면(=결제가 우리보다 먼저 확정됨) 만료 처리를 건너뛴다.
            Seat seat = seatRepository.findByIdForUpdate(seatId).orElse(null);
            if (seat == null || seat.getStatus() != SeatStatus.HELD) {
                continue;
            }

            reservation.expire();
            seat.release();
            seatHoldRepository.deleteById(seatId);

            scheduleRepository.findByIdForUpdate(seat.getSchedule().getId())
                    .ifPresent(schedule -> schedule.increaseRemaining());
        }

        // 예매 생성 전에 죽은 고아 hold 도 정리
        seatHoldRepository.deleteAll(seatHoldRepository.findByExpiresAtBefore(now));

        if (!expired.isEmpty()) {
            log.info("만료 선점 해제 {}건", expired.size());
        }
    }
}
