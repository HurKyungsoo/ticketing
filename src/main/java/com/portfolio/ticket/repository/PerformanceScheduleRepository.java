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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PerformanceSchedule s where s.id = :id")
    Optional<PerformanceSchedule> findByIdForUpdate(@Param("id") Long id);
}
