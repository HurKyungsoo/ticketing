package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 전략 선택 + 낙관적 락 재시도 + 분산 락 획득/해제 담당.
 * 재시도와 분산 락은 반드시 트랜잭션 "밖"에서 돌아야 하므로 서비스와 분리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationFacade {

    private static final int MAX_RETRY = 3;
    private static final long RETRY_BACKOFF_MS = 30L;

    /**
     * 분산 락 TTL. 선점 시간(10분)이 아니라 "예매 행을 만드는 트랜잭션" 길이만 덮으면 되므로 짧게 잡는다.
     * 락을 쥔 서버가 죽어도 이 시간이 지나면 좌석이 풀린다. DB 락 타임아웃(3초)과 같은 값으로 맞췄다.
     */
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private final ReservationService reservationService;
    private final RedisSeatLock seatLock;

    public Reservation hold(HoldStrategy strategy, Long seatId, Long memberId) {
        return switch (strategy) {
            case NONE -> reservationService.holdWithoutLock(seatId, memberId);
            case PESSIMISTIC -> reservationService.holdWithPessimisticLock(seatId, memberId);
            case UNIQUE -> reservationService.holdWithUniqueConstraint(seatId, memberId);
            case OPTIMISTIC -> holdWithRetry(seatId, memberId);
            case DISTRIBUTED -> holdWithDistributedLock(List.of(seatId), memberId);
        };
    }

    /**
     * Redis 분산 락. 락 획득/해제는 반드시 트랜잭션 "밖"에서 한다 —
     * 트랜잭션 안에서 풀어버리면 커밋 전에 다음 요청이 락을 잡아 아직 반영되지 않은
     * 좌석 상태를 읽게 된다(낙관적 락 재시도를 밖에 둔 것과 같은 이유).
     *
     * 락을 쥔 동안에는 이 좌석에 접근하는 주체가 하나뿐이므로, 안쪽 DB 작업은
     * 별도 DB 락 없이 수행한다. 즉 상호배제의 책임이 DB 에서 Redis 로 옮겨간 형태다.
     */
    private Reservation holdWithDistributedLock(List<Long> seatIds, Long memberId) {
        List<Long> sorted = seatIds.stream().distinct().sorted().toList();
        String token = UUID.randomUUID().toString();   // 소유자 식별용 (남의 락 해제 방지)

        List<Long> failed;
        try {
            failed = seatLock.tryLockAll(sorted, token, LOCK_TTL);
        } catch (RedisConnectionFailureException e) {
            throw new IllegalStateException(
                    "Redis 에 연결할 수 없어 DISTRIBUTED 전략을 사용할 수 없습니다. (docker-compose 의 redis 를 띄워주세요)");
        }

        if (!failed.isEmpty()) {
            throw new PartialSeatHoldException("다른 사용자가 선점 중인 좌석이 있습니다.", failed);
        }

        try {
            return sorted.size() == 1
                    ? reservationService.holdWithoutLock(sorted.get(0), memberId)
                    : reservationService.holdMultipleWithoutLock(sorted, memberId);
        } finally {
            seatLock.unlockAll(sorted, token);
        }
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
            case DISTRIBUTED -> holdWithDistributedLock(seatIds, memberId);
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
