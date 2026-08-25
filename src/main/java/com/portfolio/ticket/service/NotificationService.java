package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Notification;
import com.portfolio.ticket.domain.NotificationType;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.RestockSubscription;
import com.portfolio.ticket.domain.SavedSearch;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.RestockSubscriptionRepository;
import com.portfolio.ticket.repository.ReviewRepository;
import com.portfolio.ticket.repository.SavedSearchRepository;
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
    private final ReservationRepository reservationRepository;
    private final ReviewRepository reviewRepository;
    private final SavedSearchRepository savedSearchRepository;
    private final PerformanceScheduleRepository performanceScheduleRepository;

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
            createScheduleOpened(event.performanceId());
        } catch (Exception e) {
            log.warn("회차 오픈 알림 생성 실패(무시하고 계속). performanceId={}, msg={}",
                    event.performanceId(), e.getMessage());
        }
    }

    private void createScheduleOpened(Long performanceId) {
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
            if (createIfAbsent(wish.getMemberId(), performance, NotificationType.SCHEDULE_OPENED, now)) {
                created++;
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

    /**
     * 새로 등록된 공연을 저장해 둔 검색 조건과 맞춰 보고, 맞는 사람에게 알림을 만든다.
     *
     * <p>{@code onScheduleOpened} 와 같은 이유로 {@code AFTER_COMMIT} + {@code REQUIRES_NEW} 다 —
     * 발행 지점(PerformanceSyncService.sync)이 수백 건을 도는 배치라, 그 트랜잭션 안에서
     * 알림까지 만들면 알림 실패가 정상 수집분까지 롤백시킬 수 있다.
     *
     * <p>저장 검색을 전부 훑는다({@code findAll}) — 공연 하나가 등록될 때마다 도는 배치라
     * 저장 검색이 아주 많아지면 부담이 늘 수 있지만, 지금 규모(회원 수·저장 검색 수)에서는
     * 전체를 인메모리로 거르는 편이 회원마다 개별 쿼리를 날리는 것보다 단순하고 빠르다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPerformanceRegistered(PerformanceRegisteredEvent event) {
        try {
            matchSavedSearches(event.performanceId());
        } catch (Exception e) {
            log.warn("저장 검색 매칭 알림 생성 실패(무시하고 계속). performanceId={}, msg={}",
                    event.performanceId(), e.getMessage());
        }
    }

    private void matchSavedSearches(Long performanceId) {
        List<SavedSearch> searches = savedSearchRepository.findAll();
        if (searches.isEmpty()) {
            return;
        }
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) {
            return;   // 알림을 만들기 전에 정제 배치가 지운 경우
        }
        List<PerformanceSchedule> schedules =
                performanceScheduleRepository.findByPerformanceIdOrderByShowAtAsc(performanceId);

        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        for (SavedSearch search : searches) {
            if (!search.matches(performance, schedules)) {
                continue;
            }
            if (createIfAbsent(search.getMemberId(), performance, NotificationType.SAVED_SEARCH_MATCH, now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("저장 검색 매칭 알림 생성. performanceId={}, 대상 {}명", performanceId, created);
        }
    }

    /**
     * 내일 공연되는 확정 예매에 임박 알림을 만든다. 매일 아침 도는 배치({@link
     * ReservationNotificationScheduler})에서 부른다.
     *
     * <p>날짜(하루) 단위로 구간을 잡는다 — 배치가 몇 시에 돌든 "내일 하루 안의 회차"는
     * 전부 걸리게 하려는 것이라, 배치 실행 시각을 기준으로 "지금부터 24시간"을 잡으면
     * 실행 시각에 따라 경계가 흔들린다.
     */
    @Transactional
    public int createScheduleReminders(LocalDateTime now) {
        LocalDateTime tomorrowStart = now.toLocalDate().plusDays(1).atStartOfDay();
        List<Reservation> reservations = reservationRepository.findByStatusAndScheduleShowAtBetween(
                ReservationStatus.CONFIRMED, tomorrowStart, tomorrowStart.plusDays(1));

        int created = 0;
        for (Reservation reservation : reservations) {
            if (createIfAbsent(reservation.getMemberId(), reservation.getSchedule().getPerformance(),
                    NotificationType.SCHEDULE_REMINDER, now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("공연 임박 알림 생성. {}건", created);
        }
        return created;
    }

    /**
     * 어제 공연이 지난 확정 예매에 관람평 요청 알림을 만든다. {@link #createScheduleReminders}
     * 와 같은 배치에서 부른다.
     *
     * <p>이미 관람평을 쓴 사람은 뺀다 — 관람 직후 바로 쓸 수도 있으므로(자격은 회차가
     * 지나는 순간 생긴다), 다음 날 배치가 돌 때까지 기다리지 않고 먼저 썼을 수 있다.
     * 이미 한 일을 다시 요청하는 건 배치 순서가 아니라 실제로 안 겪은 사람에게만 가야
     * 알림 기능이 신뢰를 잃지 않는다.
     */
    @Transactional
    public int createReviewRequests(LocalDateTime now) {
        LocalDateTime yesterdayStart = now.toLocalDate().minusDays(1).atStartOfDay();
        List<Reservation> reservations = reservationRepository.findByStatusAndScheduleShowAtBetween(
                ReservationStatus.CONFIRMED, yesterdayStart, yesterdayStart.plusDays(1));

        int created = 0;
        for (Reservation reservation : reservations) {
            Performance performance = reservation.getSchedule().getPerformance();
            if (reviewRepository.findByMemberIdAndPerformanceId(reservation.getMemberId(), performance.getId())
                    .isPresent()) {
                continue;
            }
            if (createIfAbsent(reservation.getMemberId(), performance, NotificationType.REVIEW_REQUESTED, now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("관람평 요청 알림 생성. {}건", created);
        }
        return created;
    }

    /**
     * 세 배치(회차 오픈·공연 임박·관람평 요청)가 공유하는 저장 로직. 유니크 제약(member_id,
     * performance_id, type)이 최종 방어지만, 매일 도는 배치에서 매번 예외를 던지게 두면
     * 로그가 지저분해지고 롤백 마킹 위험도 있어 저장 전에 먼저 걸러낸다.
     *
     * @return 실제로 새로 만들었으면 true, 이미 있어서(사전 검사든 경합이든) 건너뛰었으면 false
     */
    private boolean createIfAbsent(Long memberId, Performance performance, NotificationType type, LocalDateTime now) {
        if (notificationRepository.existsByMemberIdAndPerformanceIdAndType(memberId, performance.getId(), type)) {
            return false;
        }
        try {
            notificationRepository.saveAndFlush(Notification.builder()
                    .memberId(memberId)
                    .performance(performance)
                    .type(type)
                    .createdAt(now)
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            // 위 검사와 저장 사이에 다른 실행이 먼저 넣은 경우. 이미 보낸 것이므로 넘어간다.
            log.debug("알림 중복(이미 보냄). memberId={}, performanceId={}, type={}",
                    memberId, performance.getId(), type);
            return false;
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
     * 읽음 처리 후 어디로 보낼지 정하는 데 필요한 값만 담는다. {@link Notification} 자체를
     * 돌려주면 컨트롤러가 트랜잭션 밖에서 지연 로딩(performance)을 건드릴 위험이 있어,
     * 트랜잭션 안에서 다 뽑아 넘긴다. {@code scheduleId} 는 회차를 아는 알림(SEAT_AVAILABLE)
     * 에서만 채워진다 — 컨트롤러가 이 값의 유무로 좌석도/공연 상세를 가른다.
     */
    public record ReadResult(Long performanceId, Long scheduleId, NotificationType type) {}

    /**
     * 알림을 눌러 넘어갈 때 읽음 처리하고, 리다이렉트에 필요한 값을 돌려준다.
     *
     * <p>남의 알림 id 를 넣으면 조회 단계에서 걸러져({@code findByIdAndMemberId}) 빈 값이 온다 —
     * 읽음 처리도 안 되고, 그 알림이 존재하는지도 알 수 없다.
     *
     * <p>{@code schedule}·{@code performance} 를 트랜잭션 안에서(이 메서드 안에서) 미리 읽어
     * {@link ReadResult} 에 담는다 — 엔티티를 그대로 돌려주면 트랜잭션이 끝난 뒤 컨트롤러에서
     * 지연 로딩을 건드리는 순간 LazyInitializationException 이 난다({@code open-in-view=false}).
     */
    @Transactional
    public Optional<ReadResult> markRead(Long notificationId, Long memberId) {
        return notificationRepository.findByIdAndMemberId(notificationId, memberId)
                .map(n -> {
                    n.markRead(LocalDateTime.now());
                    Long scheduleId = n.getSchedule() != null ? n.getSchedule().getId() : null;
                    return new ReadResult(n.getPerformance().getId(), scheduleId, n.getType());
                });
    }
}
