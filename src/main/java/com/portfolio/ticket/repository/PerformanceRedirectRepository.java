package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.PerformanceRedirect;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRedirectRepository extends JpaRepository<PerformanceRedirect, Long> {

    /**
     * 남긴 공연이 다시 다른 공연에 합쳐질 때, 그쪽을 가리키던 옛 기록도 새로 남긴 공연을 가리키게 한다.
     * 그래야 A → B → C 를 두 번 건너지 않고 한 번에 간다(상세 화면은 한 번만 찾아본다).
     */
    @Modifying
    @Query("update PerformanceRedirect r set r.toId = :to where r.toId = :from")
    int repoint(@Param("from") Long from, @Param("to") Long to);
}
