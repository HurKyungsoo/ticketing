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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ApplicationEventPublisher events;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void releaseExpiredHolds() {
        LocalDateTime now = LocalDateTime.now();

        List<Reservation> expired =
                reservationRepository.findByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, now);

        for (Reservation reservation : expired) {
            // 락 순서: Seat(오름차순) -> PerformanceSchedule (CLAUDE.md 동시성 규칙).
            // 결제 확정(ReservationService.confirmPayment)도 같은 좌석들을 잠그므로,
            // 이미 SOLD 로 넘어간 좌석은(=결제가 우리보다 먼저 확정됨) 건드리지 않고 건너뛴다.
            List<Long> sortedSeatIds = reservation.getSeats().stream()
                    .map(Seat::getId).sorted().toList();

            List<Seat> releasable = new ArrayList<>();
            for (Long seatId : sortedSeatIds) {
                Seat seat = seatRepository.findByIdForUpdate(seatId).orElse(null);
                if (seat != null && seat.getStatus() == SeatStatus.HELD) {
                    releasable.add(seat);
                }
            }
            if (releasable.isEmpty()) {
                continue;
            }

            reservation.expire();
            for (Seat seat : releasable) {
                seat.release();
                seatHoldRepository.deleteById(seat.getId());
            }

            Long scheduleId = releasable.get(0).getSchedule().getId();
            int releasedCount = releasable.size();
            scheduleRepository.findByIdForUpdate(scheduleId)
                    .ifPresent(schedule -> {
                        for (int i = 0; i < releasedCount; i++) {
                            schedule.increaseRemaining();
                        }
                    });
            // 취소표 알림 대상. ReservationService.cancel() 과 같은 이유로 커밋 후 처리된다.
            events.publishEvent(new SeatsReleasedEvent(scheduleId));
        }

        // 예매 생성 전에 죽은 고아 hold 도 정리
        seatHoldRepository.deleteAll(seatHoldRepository.findByExpiresAtBefore(now));

        if (!expired.isEmpty()) {
            log.info("만료 선점 해제 {}건", expired.size());
        }
    }
}
