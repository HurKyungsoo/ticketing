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

    List<Seat> findByScheduleIdOrderBySectionAscSeatNoAsc(Long scheduleId);

    long countByScheduleIdAndStatus(Long scheduleId, SeatStatus status);

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
