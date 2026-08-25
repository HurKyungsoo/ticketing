package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.SavedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {

    /** 마이페이지 "저장한 검색" 목록. 최근 저장한 순. */
    List<SavedSearch> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    /** 저장 한도(MAX_SAVED_SEARCHES_PER_MEMBER) 체크용. */
    long countByMemberId(Long memberId);

    /**
     * 삭제는 본인 것만 — WishlistRepository.deleteByMemberIdAndPerformanceId 와 같은 이유로
     * 단일 DELETE 문을 쓴다. member_id 조건이 없는 findById+delete 였다면 남의 저장 검색
     * id 를 넣어도 지워지는 IDOR 취약점이 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SavedSearch s where s.id = :id and s.memberId = :memberId")
    int deleteByIdAndMemberId(@Param("id") Long id, @Param("memberId") Long memberId);
}
