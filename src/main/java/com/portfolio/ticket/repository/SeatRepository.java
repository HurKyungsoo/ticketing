package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(Long scheduleId);

    long countByScheduleIdAndStatus(Long scheduleId, SeatStatus status);

    /**
     * 이 회원이 이 회차에 이미 보유한 좌석 수 (결제대기 HELD + 확정 SOLD 합산).
     * 1인당 매수 제한 체크용. AVAILABLE(취소/만료로 풀린 좌석)은 reservation 이 끊겨 있어 제외된다.
     */
    long countByScheduleIdAndReservationMemberIdAndStatusIn(
            Long scheduleId, Long memberId, List<SeatStatus> statuses);

    /**
     * 비관적 쓰기 락 (SELECT ... FOR UPDATE).
     * 타임아웃을 주지 않으면 오픈런 때 커넥션이 통째로 묶인다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    /** 낙관적 락 비교용 - 락 힌트 없이 그냥 조회 */
    @Query("select s from Seat s where s.id = :id")
    Optional<Seat> findByIdPlain(@Param("id") Long id);
}
