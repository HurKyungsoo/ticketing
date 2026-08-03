package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 찜 쓰기의 트랜잭션 경계. {@link WishlistService} 와 분리한 이유는 {@code ReservationFacade} 와 같다 —
 * <b>유니크 제약 위반을 삼키는 쪽은 트랜잭션 밖이어야 한다.</b>
 *
 * <p>flush 가 제약 위반으로 실패하면 영속성 컨텍스트가 어긋난 상태가 되고 트랜잭션은
 * 롤백 대상으로 표시된다. 그 안에서 예외를 잡고 정상 반환하면 커밋 시점에 다시 터진다.
 * 그래서 여기서는 잡지 않고 그대로 올려보내 트랜잭션을 깨끗이 롤백시키고, 해석은 호출부가 한다.
 */
@Service
@RequiredArgsConstructor
public class WishlistWriter {

    private final WishlistRepository wishlistRepository;
    private final PerformanceRepository performanceRepository;

    /** 지운 게 있으면 true. 토글의 해제 쪽이다. */
    @Transactional
    public boolean removeIfPresent(Long memberId, Long performanceId) {
        return wishlistRepository.deleteByMemberIdAndPerformanceId(memberId, performanceId) > 0;
    }

    /**
     * 찜 추가. 이미 있으면 {@code DataIntegrityViolationException} 이 <b>그대로 올라간다</b>.
     *
     * <p>{@code saveAndFlush} 인 게 중요하다 — 그냥 {@code save} 면 제약 검사가 커밋까지
     * 미뤄져서, 호출부가 트랜잭션 경계에서 예외를 받지 못하고 그 바깥에서 받게 된다.
     */
    @Transactional
    public void add(Long memberId, Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));

        wishlistRepository.saveAndFlush(Wishlist.builder()
                .memberId(memberId)
                .performance(performance)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
