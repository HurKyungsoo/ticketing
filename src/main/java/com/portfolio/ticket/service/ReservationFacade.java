package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /** 좌석 여러 개를 한 번에 선점한다. 전략별 분기는 단일 좌석과 동일하다. */
    public Reservation holdMultiple(HoldStrategy strategy, List<Long> seatIds, Long memberId) {
        return switch (strategy) {
            case NONE -> reservationService.holdMultipleWithoutLock(seatIds, memberId);
            case PESSIMISTIC -> reservationService.holdMultipleWithPessimisticLock(seatIds, memberId);
            case UNIQUE -> reservationService.holdMultipleWithUniqueConstraint(seatIds, memberId);
            case OPTIMISTIC -> holdMultipleWithRetry(seatIds, memberId);
        };
    }

    /**
     * PartialSeatHoldException(특정 좌석이 이미 선점됨)은 재시도해도 결과가 같으므로 그대로 흘려보내고,
     * ObjectOptimisticLockingFailureException(버전 충돌)만 재시도한다.
     */
    private Reservation holdMultipleWithRetry(List<Long> seatIds, Long memberId) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return reservationService.holdMultipleWithOptimisticLock(seatIds, memberId);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("낙관적 락 충돌. seatIds={}, attempt={}", seatIds, attempt);
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
