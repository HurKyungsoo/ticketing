package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 공연 상세의 관람평 목록. 최근 순. */
    List<Review> findByPerformanceIdOrderByCreatedAtDesc(Long performanceId);

    /** 이 사람이 이 공연에 이미 썼는지. 화면이 "쓰기"와 "고치기" 중 무엇을 보여줄지 정한다. */
    Optional<Review> findByMemberIdAndPerformanceId(Long memberId, Long performanceId);

    /**
     * 공연이 지워질 때 함께 정리한다. 찜·알림과 같은 이유·같은 방식이다 —
     * FK 가 걸려 있어 남겨두면 공연 삭제가 막힌다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Review r where r.performance.id = :performanceId")
    int deleteByPerformanceId(@Param("performanceId") Long performanceId);
}
