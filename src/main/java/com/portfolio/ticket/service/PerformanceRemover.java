package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.ReviewRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집 배치가 공연 하나를 통째로 지울 때(다른 소스의 같은 공연, 모집 공고) 쓰는 단일 경로.
 *
 * <p><b>회차는 {@link ScheduleRebuildService#removeIfUntouched} 로 하나씩 지운다.</b> 그래야 락 순서가
 * Seat → PerformanceSchedule 로 좌석 선점과 같고(CLAUDE.md 동시성 규칙 1), 잠근 뒤에 비어 있는지
 * 확인하므로 확인과 삭제 사이에 누가 좌석을 잡을 틈이 없다. 전에는 "예매가 있나"를 잠금 없이 본 뒤
 * 좌석을 지워서, 그 사이 누군가 선점한 좌석이 선점째 사라질 수 있었다.
 *
 * <p>회차마다 트랜잭션이 따로라 좌석 락을 공연 하나 내내 붙잡지 않는다. 대신 중간 회차에서 멈추면
 * 앞 회차만 지워진 채 공연이 남는다 — 그 공연은 어차피 지우려던(중복이거나 공연이 아닌) 항목이고,
 * 남는 쪽엔 손댄 사람이 있다는 뜻이므로 지우는 것보다 낫다. 그래서 먼저 예매 여부를 싸게 걸러
 * 이런 경우를 줄인다.
 */
@Service
@RequiredArgsConstructor
public class PerformanceRemover {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ReservationRepository reservationRepository;
    private final WishlistRepository wishlistRepository;
    private final NotificationRepository notificationRepository;
    private final ReviewRepository reviewRepository;
    private final ScheduleRebuildService scheduleRebuildService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 아무도 손대지 않은 공연이면 지운다. 트랜잭션 밖에서 불러야 한다 — 회차마다 따로 커밋한다.
     *
     * @param keeperId 같은 공연으로 남길 쪽. 있으면 지우는 쪽의 찜·주소를 그쪽으로 옮긴다. 없으면 null.
     * @return 지웠으면 true. 예매·선점·빈자리 알림 등이 걸린 회차가 있어 남겼으면 false.
     */
    public boolean removeIfUntouched(Long performanceId, Long keeperId) {
        if (reservationRepository.existsByPerformanceId(performanceId)) {
            return false;
        }
        for (Long scheduleId : scheduleRepository.findAllIdsByPerformanceId(performanceId)) {
            if (!scheduleRebuildService.removeIfUntouched(scheduleId)) {
                return false;
            }
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> removeEmpty(performanceId, keeperId)));
    }

    /** 회차가 다 지워진 공연을 지운다. 그 사이 회차가 새로 붙었으면 남긴다. */
    private boolean removeEmpty(Long performanceId, Long keeperId) {
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) {
            return false;
        }
        if (!scheduleRepository.findAllIdsByPerformanceId(performanceId).isEmpty()) {
            return false;
        }
        Performance keeper = keeperId == null ? null : performanceRepository.findById(keeperId).orElse(null);
        if (keeper != null) {
            keeper.fillMissingLocation(performance.getAddress(), performance.getLatitude(), performance.getLongitude());
            moveWishlists(performance, keeper);
        }
        // 찜·알림·관람평은 공연을 FK 로 참조한다. 공연이 사라지면 가리킬 대상이 없다 —
        // 금전·입장 권리가 걸린 예매와 달라서 함께 지운다(예매가 있으면 위에서 이미 남겼다).
        wishlistRepository.deleteByPerformanceId(performanceId);
        notificationRepository.deleteByPerformanceId(performanceId);
        reviewRepository.deleteByPerformanceId(performanceId);
        performanceRepository.delete(performance);
        return true;
    }

    /** 같은 공연이니 사용자의 "나중에 볼까"는 남는 쪽에서도 그대로 유효하다. */
    private void moveWishlists(Performance from, Performance to) {
        for (Wishlist w : wishlistRepository.findByPerformanceId(from.getId())) {
            if (wishlistRepository.existsByMemberIdAndPerformanceId(w.getMemberId(), to.getId())) continue;
            wishlistRepository.save(Wishlist.builder()
                    .memberId(w.getMemberId()).performance(to).createdAt(w.getCreatedAt()).build());
        }
    }
}
