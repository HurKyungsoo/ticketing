package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Notification;
import com.portfolio.ticket.domain.NotificationType;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final WishlistRepository wishlistRepository;
    private final PerformanceRepository performanceRepository;

    /**
     * 회차가 열린 공연을 찜해 둔 사람들에게 알림을 만든다.
     *
     * <p>{@code AFTER_COMMIT} 이라 동기화가 실제로 커밋된 뒤에만 돈다(발행 이유는
     * {@link ScheduleOpenedEvent} 참고). 그 시점엔 원래 트랜잭션이 끝났으므로
     * {@code REQUIRES_NEW} 로 자기 트랜잭션을 연다 — 안 그러면 저장이 안 되거나
     * 트랜잭션 없이 도는 상태가 된다.
     *
     * <p>예외를 밖으로 안 던진다. 이 리스너가 터져도 동기화는 이미 커밋됐고, 알림 하나
     * 때문에 배치 로그가 오염되면 정작 수집 결과를 못 읽는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onScheduleOpened(ScheduleOpenedEvent event) {
        try {
            create(event.performanceId());
        } catch (Exception e) {
            log.warn("회차 오픈 알림 생성 실패(무시하고 계속). performanceId={}, msg={}",
                    event.performanceId(), e.getMessage());
        }
    }

    private void create(Long performanceId) {
        List<Wishlist> wishes = wishlistRepository.findByPerformanceId(performanceId);
        if (wishes.isEmpty()) {
            return;
        }
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) {
            return;   // 알림을 만들기 전에 정제 배치가 지운 경우
        }

        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (Wishlist wish : wishes) {
            // 유니크 제약(uk_notification)이 최종 방어지만, 매일 도는 배치에서 매번 예외를
            // 던지게 두면 로그가 지저분해지고 롤백 마킹 위험도 있다. 미리 걸러낸다.
            if (notificationRepository.existsByMemberIdAndPerformanceIdAndType(
                    wish.getMemberId(), performanceId, NotificationType.SCHEDULE_OPENED)) {
                continue;
            }
            try {
                notificationRepository.saveAndFlush(Notification.builder()
                        .memberId(wish.getMemberId())
                        .performance(performance)
                        .type(NotificationType.SCHEDULE_OPENED)
                        .createdAt(now)
                        .build());
                created++;
            } catch (DataIntegrityViolationException e) {
                // 위 검사와 저장 사이에 다른 실행이 먼저 넣은 경우. 이미 보낸 것이므로 넘어간다.
                log.debug("알림 중복(이미 보냄). memberId={}, performanceId={}", wish.getMemberId(), performanceId);
            }
        }
        if (created > 0) {
            log.info("회차 오픈 알림 생성. performanceId={}, 대상 {}명", performanceId, created);
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> findMine(Long memberId) {
        return notificationRepository.findWithPerformanceByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public long countUnread(Long memberId) {
        return notificationRepository.countByMemberIdAndReadAtIsNull(memberId);
    }

    /**
     * 알림을 눌러 공연으로 넘어갈 때 읽음 처리하고, 넘어갈 공연 id 를 돌려준다.
     *
     * <p>남의 알림 id 를 넣으면 조회 단계에서 걸러져({@code findByIdAndMemberId}) 빈 값이 온다 —
     * 읽음 처리도 안 되고, 그 알림이 존재하는지도 알 수 없다.
     */
    @Transactional
    public Optional<Long> markRead(Long notificationId, Long memberId) {
        return notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .map(n -> {
                    n.markRead(LocalDateTime.now());
                    return n.getPerformance().getId();
                });
    }
}
