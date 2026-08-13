package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.RestockSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RestockSubscriptionRepository extends JpaRepository<RestockSubscription, Long> {

    boolean existsByMemberIdAndScheduleId(Long memberId, Long scheduleId);

    /**
     * 토글의 해제 쪽. WishlistRepository.deleteByMemberIdAndPerformanceId 와 같은 이유로
     * 파생 쿼리 대신 단일 DELETE 문을 쓴다 — 같은 행을 두 요청이 동시에 지우면(따닥 클릭)
     * 파생 쿼리(select 후 개별 delete)는 뒤늦은 쪽이 낙관적 락 실패로 터진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RestockSubscription s where s.memberId = :memberId and s.schedule.id = :scheduleId")
    int deleteByMemberIdAndScheduleId(@Param("memberId") Long memberId, @Param("scheduleId") Long scheduleId);

    /** 좌석이 풀렸을 때(Seat.release()) 이 회차를 구독한 사람들을 찾는다. */
    List<RestockSubscription> findByScheduleId(Long scheduleId);

    /**
     * 공연 상세에서 매진 회차마다 "지금 구독 중인지" 버튼 상태를 정할 때 쓴다. 회차마다
     * existsByMemberIdAndScheduleId 를 따로 부르면 매진 회차 수만큼 조회가 나가므로(N+1),
     * 화면이 한 번에 물어본 회차 id 목록을 통째로 넘긴다.
     */
    List<RestockSubscription> findByMemberIdAndScheduleIdIn(Long memberId, List<Long> scheduleIds);

    /**
     * 회차가 지워질 때(공연 cascade) 함께 정리한다 — schedule_id 에 FK 가 걸려 있어
     * 남으면 삭제가 막힌다. wishlist/notification 이 공연 삭제 때 같이 정리되는 것과 같은 이유.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RestockSubscription s where s.schedule.id = :scheduleId")
    int deleteByScheduleId(@Param("scheduleId") Long scheduleId);
}
