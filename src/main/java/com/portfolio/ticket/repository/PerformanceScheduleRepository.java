package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.PerformanceSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

    List<PerformanceSchedule> findByPerformanceIdOrderByShowAtAsc(Long performanceId);

    /** 좌석 배치도 화면용. open-in-view=false 라 뷰 렌더링 전에 performance 를 미리 가져와야 한다. */
    @Query("select s from PerformanceSchedule s join fetch s.performance where s.id = :id")
    Optional<PerformanceSchedule> findWithPerformanceById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PerformanceSchedule s where s.id = :id")
    Optional<PerformanceSchedule> findByIdForUpdate(@Param("id") Long id);
}
