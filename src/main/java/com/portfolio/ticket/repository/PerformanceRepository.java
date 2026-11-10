package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.SourceType;
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

    /**
     * 상세를 다시 받아야 하는 상영 중 공연 — 공연 시각·가격 원문이 둘 다 없고, 아직 다시 받아 보지 않은 것.
     * 원문을 저장하기 전에 들어온 공연은 가격이 있어도 원문이 없어 여기 걸린다.
     * 가격·공연장까지 빠진 공연을 먼저, 그다음 가까운 공연부터 채운다.
     */
    @Query("""
            select p from Performance p
            where p.sourceType = :sourceType and p.endDate >= :today
              and p.showTimeGuidance is null and p.priceGuidance is null and p.detailFetchedOn is null
            order by case when p.basePrice is null and p.venueHallId is null then 0 else 1 end,
                     p.startDate asc, p.id asc
            """)
    List<Performance> findDetailBackfillTargets(@Param("sourceType") SourceType sourceType,
                                                @Param("today") LocalDate today, Pageable pageable);

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

    /** 기간이 [start, end] 와 겹치는 공연 — 다른 소스의 같은 공연(중복) 후보를 좁힌다 */
    @Query("select p from Performance p where p.startDate <= :end and p.endDate >= :start")
    List<Performance> findOverlapping(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 아직 안 끝난 공연 — 중복 정리 대상 */
    List<Performance> findByEndDateGreaterThanEqual(LocalDate today);

    /** 아직 안 끝난 공연의 ID — 사이트맵용. 엔티티까지 읽을 필요가 없다. */
    @Query("select p.id from Performance p where p.endDate >= :today order by p.id")
    List<Long> findIdsEndingOnOrAfter(@Param("today") LocalDate today);
}
