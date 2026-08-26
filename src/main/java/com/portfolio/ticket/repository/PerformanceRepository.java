package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Performance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    Optional<Performance> findByExternalId(String externalId);

    /** 상세 화면용. open-in-view=false 라 뷰 렌더링 전에 bookingLinks 를 미리 가져와야 한다. */
    @Query("select p from Performance p left join fetch p.bookingLinks where p.id = :id")
    Optional<Performance> findByIdWithBookingLinks(@Param("id") Long id);

    Page<Performance> findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate today, Pageable pageable);

    /**
     * 아직 안 끝났는데(endDate >= today) 미래 회차가 하나도 없는 공연. KOPIS 증분 수집은
     * 원본이 변경된 건만 다시 내려오므로, 오래 안 건드려진 공연은 PerformanceSyncService
     * 의 topUpSchedules(외부 응답 기준)가 아예 호출되지 않아 계속 비어 있을 수 있다 —
     * 그 사각지대를 잡는 topUpStaleSchedules 전용 조회.
     */
    @Query("""
            select p.id from Performance p
            where p.endDate >= :today
            and p.id not in (
                select s.performance.id from PerformanceSchedule s where s.showAt >= :now
            )
            """)
    List<Long> findIdsWithNoFutureSchedule(@Param("today") LocalDate today, @Param("now") LocalDateTime now);
}
