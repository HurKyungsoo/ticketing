package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
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
    private final SeatHoldRepository seatHoldRepository;
    private final PerformanceScheduleRepository scheduleRepository;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now();

        List<Reservation> expired =
                reservationRepository.findByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, now);

        for (Reservation reservation : expired) {
            reservation.expire();
            reservation.getSeat().release();
            seatHoldRepository.deleteById(reservation.getSeat().getId());

            scheduleRepository.findByIdForUpdate(reservation.getSeat().getSchedule().getId())
                    .ifPresent(schedule -> schedule.increaseRemaining());
        }

        // 예매 생성 전에 죽은 고아 hold 도 정리
        seatHoldRepository.deleteAll(seatHoldRepository.findByExpiresAtBefore(now));

        if (!expired.isEmpty()) {
            log.info("만료 선점 해제 {}건", expired.size());
        }
    }
}
