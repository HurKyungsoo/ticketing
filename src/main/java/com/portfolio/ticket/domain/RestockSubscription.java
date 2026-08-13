package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 매진 회차의 "빈자리 알림 받기" 구독.
 *
 * <p>{@link Wishlist}(공연 단위 — "나중에 볼까")와 달리 <b>회차</b>에 건다. 사용자가 원하는 건
 * "이 공연"이 아니라 "이 날짜·시각"의 빈자리라서다. 다른 회차가 열려도 이 구독과는 무관하다.
 *
 * <p>{@code memberId} 를 연관관계가 아니라 값으로 든 건 {@link Notification} 과 같은 이유다.
 * {@code schedule} 은 FK 를 건다 — 회차는 공연의 cascade 로 함께 지워진다
 * (PerformanceSyncService.purgeNonPerformances).
 *
 * <p><b>일회성이다.</b> {@link com.portfolio.ticket.service.NotificationService} 가 이 구독으로
 * 알림을 보내고 나면 행을 지운다 — 좌석이 풀릴 때마다 계속 알리면 취소가 이어지는 회차에서
 * 스팸이 된다. 다시 받고 싶으면 사용자가 새로 구독해야 한다.
 */
@Entity
@Table(
    name = "restock_subscription",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_restock_subscription", columnNames = {"member_id", "schedule_id"}),
    // 좌석이 풀렸을 때 "이 회차를 구독한 사람"을 찾는 조회(NotificationService.onSeatsReleased).
    indexes = @Index(name = "idx_restock_subscription_schedule", columnList = "schedule_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RestockSubscription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id")
    private PerformanceSchedule schedule;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
