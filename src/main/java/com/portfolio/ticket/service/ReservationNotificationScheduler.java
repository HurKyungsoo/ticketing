package com.portfolio.ticket.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 예매(회차 시각) 기준으로 도는 알림 배치. 매일 아침 한 번, 두 가지를 만든다 —
 * 내일 공연되는 확정 예매에 임박 알림, 어제 공연이 지난 확정 예매에 관람평 요청 알림.
 *
 * <p>새벽 4시 공연 수집({@link com.portfolio.ticket.external.PerformanceSyncScheduler})과
 * 시각을 겹치지 않게 뒀다 — 그쪽이 먼저 끝난 뒤 도는 게 자연스럽고, 굳이 같은 시각에 몰아
 * 부하를 겹칠 이유가 없다. 9시대는 사용자가 알림을 볼 가능성이 높은 시간대이기도 하다.
 */
@Component
@RequiredArgsConstructor
public class ReservationNotificationScheduler {

    private final NotificationService notificationService;

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Seoul")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        notificationService.createScheduleReminders(now);
        notificationService.createReviewRequests(now);
    }
}
