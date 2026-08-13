package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Notification;
import com.portfolio.ticket.domain.NotificationType;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.RestockSubscription;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.RestockSubscriptionRepository;
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
    private final RestockSubscriptionRepository restockSubscriptionRepository;

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

    /**
     * 매진 회차를 구독한 사람들에게 취소표 알림을 만든다.
     *
     * <p>{@code onScheduleOpened} 와 같은 이유로 {@code AFTER_COMMIT} + {@code REQUIRES_NEW} 다 —
     * 여기서는 특히 더 중요하다. 발행 지점인 {@code ReservationService.cancel()} 은 환불까지
     * 걸린 트랜잭션이라, 알림 실패가 그 트랜잭션을 롤백시키면 환불된 취소가 롤백되며 사라진다.
     *
     * <p>"정말 매진이었다가 풀렸는가"는 여기서 판정하지 않는다({@link SeatsReleasedEvent} 주석
     * 참고) — 구독을 <b>보낸 즉시 지우는 것</b>이 판정을 대신한다. 같은 회차에서 좌석이
     * 잇달아 풀려 이 리스너가 여러 번 불려도, 두 번째부터는 구독이 없어 조용히 끝난다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSeatsReleased(SeatsReleasedEvent event) {
        try {
            notifyRestockSubscribers(event.scheduleId());
        } catch (Exception e) {
            log.warn("취소표 알림 생성 실패(무시하고 계속). scheduleId={}, msg={}",
                    event.scheduleId(), e.getMessage());
        }
    }

    private void notifyRestockSubscribers(Long scheduleId) {
        List<RestockSubscription> subs = restockSubscriptionRepository.findByScheduleId(scheduleId);
        if (subs.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (RestockSubscription sub : subs) {
            PerformanceSchedule schedule = sub.getSchedule();
            try {
                notificationRepository.saveAndFlush(Notification.builder()
                        .memberId(sub.getMemberId())
                        .performance(schedule.getPerformance())
                        .schedule(schedule)
                        .type(NotificationType.SEAT_AVAILABLE)
                        .createdAt(now)
                        .build());
                created++;
            } catch (DataIntegrityViolationException e) {
                // uk_notification_schedule 위반 — 이미 보냈는데 구독이 아직 안 지워진 드문 경합.
                log.debug("취소표 알림 중복(이미 보냄). memberId={}, scheduleId={}", sub.getMemberId(), scheduleId);
            }
            // 성공하든 중복이든 구독은 지운다 — 일회성이라 어느 쪽이든 더 알릴 이유가 없다.
            restockSubscriptionRepository.delete(sub);
        }
        if (created > 0) {
            log.info("취소표 알림 생성. scheduleId={}, 대상 {}명", scheduleId, created);
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

    /** 알림을 눌렀을 때 넘어갈 곳. 회차를 아는 알림(SEAT_AVAILABLE)은 좌석도로, 아니면 공연 상세로. */
    public record ReadTarget(Long performanceId, Long scheduleId) {}

    /**
     * 알림을 눌러 넘어갈 때 읽음 처리하고, 넘어갈 곳을 돌려준다.
     *
     * <p>남의 알림 id 를 넣으면 조회 단계에서 걸러져({@code findByIdAndMemberId}) 빈 값이 온다 —
     * 읽음 처리도 안 되고, 그 알림이 존재하는지도 알 수 없다.
     *
     * <p>{@code schedule}·{@code performance} 를 트랜잭션 안에서(이 메서드 안에서) 미리 읽어
     * {@link ReadTarget} 에 담는다 — 엔티티를 그대로 돌려주면 트랜잭션이 끝난 뒤 컨트롤러에서
     * 지연 로딩을 건드리는 순간 LazyInitializationException 이 난다({@code open-in-view=false}).
     */
    @Transactional
    public Optional<ReadTarget> markRead(Long notificationId, Long memberId) {
        return notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .map(n -> {
                    n.markRead(LocalDateTime.now());
                    Long scheduleId = n.getSchedule() != null ? n.getSchedule().getId() : null;
                    return new ReadTarget(n.getPerformance().getId(), scheduleId);
                });
    }
}
