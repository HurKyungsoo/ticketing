package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.mapper.PerformanceMapper;
import com.portfolio.ticket.mapper.dto.NextShowRow;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import com.portfolio.ticket.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistWriter writer;
    private final PerformanceMapper performanceMapper;

    /**
     * 찜한 공연들의 "다음 회차"를 공연 id 로 찾을 수 있게 모아 준다.
     *
     * <p>찜 목록 카드는 목록·홈 카드와 달리 {@code PerformanceListRow} 가 아니라 JPA
     * 엔티티를 그려서, 매퍼가 같이 뽑아 주는 next_show_at 을 못 받는다. 그렇다고 이
     * 화면만 공연 기간을 적으면 같은 모양의 카드가 화면에 따라 다른 걸 말하게 된다.
     *
     * <p>찜 건수만큼 한 건씩 묻지 않고 한 번에 받는다. 회차가 남지 않은 공연은 결과에
     * 없으므로 화면에서 {@code null} 이 되고, 그때는 종전대로 기간을 적는다.
     */
    public Map<Long, String> nextShowsFor(List<Wishlist> wishes) {
        if (wishes.isEmpty()) {
            return Map.of();   // IN () 은 문법 오류라 아예 안 묻는다
        }
        LocalDate today = LocalDate.now();
        List<Long> ids = wishes.stream().map(w -> w.getPerformance().getId()).toList();
        // 문구까지 여기서 만들어 넘긴다. 시각만 넘기면 "오늘/내일" 판정을 템플릿에서 다시
        // 짜야 하고, 그러면 목록·홈과 찜의 표기가 갈릴 자리가 생긴다.
        return performanceMapper.selectNextShowAt(ids, today).stream()
                .collect(Collectors.toMap(NextShowRow::getPerformanceId,
                        r -> PerformanceListRow.labelFor(r.getNextShowAt(), today)));
    }

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
