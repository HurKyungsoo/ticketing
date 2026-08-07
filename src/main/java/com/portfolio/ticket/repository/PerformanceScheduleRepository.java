package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.PerformanceSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

    List<PerformanceSchedule> findByPerformanceIdOrderByShowAtAsc(Long performanceId);

    /**
     * 앞으로 열리는 회차가 하나라도 있는지. 회차 오픈 알림이 "없다가 생겼다"를 판정하는 데 쓴다.
     * 목록을 읽어 세지 않는 이유는 topUpSchedules 의 max 조회와 같다 — 공연당 회차가 최대
     * 90개라, 동기화가 공연마다 그 행 수를 다 읽으면 배치가 그만큼 무거워진다.
     */
    boolean existsByPerformanceIdAndShowAtAfter(Long performanceId, LocalDateTime at);

    /**
     * 회차 보충(topUpSchedules) 시 "어디까지 이미 있나"를 확인하는 용도. 공연의 전체 회차
     * 컬렉션을 로딩해 스트림으로 max 를 구하면 매 동기화마다 공연 하나당 조회 하나씩 늘어난다
     * (회차가 늘어날수록 그 행 수도 커진다) — 집계 쿼리 하나로 대신한다.
     */
    @Query("select max(s.showAt) from PerformanceSchedule s where s.performance.id = :performanceId")
    Optional<LocalDateTime> findMaxShowAtByPerformanceId(@Param("performanceId") Long performanceId);

    /** 좌석 배치도 화면용. open-in-view=false 라 뷰 렌더링 전에 performance 를 미리 가져와야 한다. */
    @Query("select s from PerformanceSchedule s join fetch s.performance where s.id = :id")
    Optional<PerformanceSchedule> findWithPerformanceById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PerformanceSchedule s where s.id = :id")
    Optional<PerformanceSchedule> findByIdForUpdate(@Param("id") Long id);
}
