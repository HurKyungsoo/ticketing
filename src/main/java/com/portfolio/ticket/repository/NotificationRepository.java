package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 알림 목록. 공연을 join fetch 하는 이유는 화면이 알림마다 공연명·포스터를 쓰기 때문이다 —
     * 안 하면 알림 수만큼 공연 조회가 나간다(N+1). schedule 은 SEAT_AVAILABLE 에서만 있어
     * left join fetch 다 — 일반 join 이면 SCHEDULE_OPENED(schedule null) 행이 통째로 빠진다.
     */
    @Query("""
           select n from Notification n
             join fetch n.performance
             left join fetch n.schedule
            where n.memberId = :memberId
            order by n.createdAt desc
           """)
    List<Notification> findWithPerformanceByMemberId(@Param("memberId") Long memberId);

    long countByMemberIdAndReadAtIsNull(Long memberId);

    boolean existsByMemberIdAndPerformanceIdAndType(
            Long memberId, Long performanceId, com.portfolio.ticket.domain.NotificationType type);

    boolean existsByMemberIdAndScheduleIdAndType(
            Long memberId, Long scheduleId, com.portfolio.ticket.domain.NotificationType type);

    Optional<Notification> findByIdAndMemberId(Long id, Long memberId);

    /**
     * 공연이 지워질 때 같이 지운다. wishlist 와 같은 이유·같은 방식이다 —
     * FK 가 걸려 있어 남겨두면 공연 삭제가 막힌다.
     */
    @Query("delete from Notification n where n.performance.id = :performanceId")
    @org.springframework.data.jpa.repository.Modifying
    int deleteByPerformanceId(@Param("performanceId") Long performanceId);
}
