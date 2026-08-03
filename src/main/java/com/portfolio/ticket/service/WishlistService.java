package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistWriter writer;

    /**
     * 찜 토글. 반환값은 <b>호출 후의 상태</b>다(true = 지금 찜한 상태).
     *
     * <p>버튼을 따닥 누르면 요청이 둘 나가고, 둘 다 "아직 없음"을 읽은 뒤 각자 insert 하려 든다.
     * 존재 여부를 먼저 확인하는 것만으로는 못 막는 검사-후-삽입 경쟁이라, 최종 판정은
     * 유니크 제약(uk_wishlist)에 맡긴다. 좌석 선점의 UNIQUE 전략과 같은 방식이다.
     *
     * <p><b>다만 여기서는 진 쪽도 실패가 아니다.</b> 좌석은 한 사람만 가져야 하지만, 찜은
     * 사용자가 원한 결과("찜해둔 상태")가 어느 쪽이 이기든 똑같이 이뤄지기 때문이다.
     * 그래서 제약 위반을 오류가 아니라 "이미 찜함"으로 해석한다.
     *
     * <p>이 메서드에 {@code @Transactional} 이 없는 게 핵심이다 — 제약 위반을 삼키는 지점이
     * 트랜잭션 안이면 롤백 대상으로 표시된 채 커밋을 시도해 다시 터진다({@link WishlistWriter} 주석).
     */
    public boolean toggle(Long memberId, Long performanceId) {
        if (writer.removeIfPresent(memberId, performanceId)) {
            return false;
        }
        try {
            writer.add(memberId, performanceId);
        } catch (DataIntegrityViolationException e) {
            // 같은 회원이 동시에 두 번 눌렀고 이 쪽이 졌다. 원하던 상태는 이미 이뤄져 있다.
            log.debug("찜 중복 요청. memberId={}, performanceId={}", memberId, performanceId);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isWishlisted(Long memberId, Long performanceId) {
        return memberId != null && wishlistRepository.existsByMemberIdAndPerformanceId(memberId, performanceId);
    }

    @Transactional(readOnly = true)
    public List<Wishlist> findMyWishlist(Long memberId) {
        return wishlistRepository.findWithPerformanceByMemberId(memberId);
    }
}
