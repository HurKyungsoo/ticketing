package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    boolean existsByMemberIdAndPerformanceId(Long memberId, Long performanceId);

    /**
     * 토글의 해제 쪽. 지운 행 수로 실제로 해제됐는지 판단한다.
     *
     * <p><b>파생 쿼리({@code deleteByMemberIdAndPerformanceId})를 쓰면 안 된다.</b> 그건 엔티티를
     * 먼저 select 한 뒤 하나씩 지우는데, 같은 행을 두 요청이 동시에 지우면 뒤늦은 쪽의 delete 가
     * 0행을 건드려 Hibernate 가 낙관적 락 실패로 터진다(실제로 겪었다 — 단독 실행에서는
     * 재현되지 않고 전체 테스트에서만 났다). 단일 DELETE 문이면 진 쪽은 그냥 0을 받는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Wishlist w where w.memberId = :memberId and w.performance.id = :performanceId")
    int deleteByMemberIdAndPerformanceId(@Param("memberId") Long memberId,
                                          @Param("performanceId") Long performanceId);

    /**
     * 마이페이지 찜 목록. {@code open-in-view=false} 라 화면에서 쓸 공연 정보를 미리 가져와야 한다
     * — 안 하면 템플릿 렌더링 시점에 LazyInitializationException 이 난다.
     */
    @Query("select w from Wishlist w join fetch w.performance " +
            "where w.memberId = :memberId order by w.createdAt desc")
    List<Wishlist> findWithPerformanceByMemberId(@Param("memberId") Long memberId);

    /**
     * 공연이 지워질 때 함께 정리한다({@code performance_id} 에 FK 가 걸려 있어 남으면 삭제가 막힌다).
     * 찜은 사용자가 만든 기록이지만 공연 자체가 사라지면 가리킬 대상이 없다 — 예매와 달리
     * 금전·입장 권리가 걸려 있지 않아 함께 지운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Wishlist w where w.performance.id = :performanceId")
    int deleteByPerformanceId(@Param("performanceId") Long performanceId);
}
