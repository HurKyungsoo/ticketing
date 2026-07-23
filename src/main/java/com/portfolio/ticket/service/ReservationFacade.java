package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 전략 선택 + 낙관적 락 재시도 담당.
 * 재시도는 반드시 트랜잭션 "밖"에서 돌아야 하므로 서비스와 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationFacade {

    private static final int MAX_RETRY = 3;
    private static final long RETRY_BACKOFF_MS = 30L;

    private final ReservationService reservationService;

    public Reservation hold(HoldStrategy strategy, Long seatId, Long memberId) {
        return switch (strategy) {
            case NONE -> reservationService.holdWithoutLock(seatId, memberId);
            case PESSIMISTIC -> reservationService.holdWithPessimisticLock(seatId, memberId);
            case UNIQUE -> reservationService.holdWithUniqueConstraint(seatId, memberId);
            case OPTIMISTIC -> holdWithRetry(seatId, memberId);
        };
    }

    private Reservation holdWithRetry(Long seatId, Long memberId) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return reservationService.holdWithOptimisticLock(seatId, memberId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("낙관적 락 충돌. seatId={}, attempt={}", seatId, attempt);
                if (attempt == MAX_RETRY) {
                    throw new SeatAlreadyTakenException("좌석 경쟁이 심합니다. 다시 시도해 주세요.");
                }
                sleep(RETRY_BACKOFF_MS * attempt);
            }
        }
        throw new SeatAlreadyTakenException("좌석 선점에 실패했습니다.");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
