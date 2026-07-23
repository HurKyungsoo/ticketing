package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Performance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    Optional<Performance> findByExternalId(String externalId);

    Page<Performance> findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate today, Pageable pageable);
}
