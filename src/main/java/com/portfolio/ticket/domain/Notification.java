package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자에게 보여줄 알림 한 건.
 *
 * <p>두 종류다 — {@link NotificationType#SCHEDULE_OPENED}(찜한 공연에 회차가 열림, 공연
 * 단위)와 {@link NotificationType#SEAT_AVAILABLE}(구독한 매진 회차에 좌석이 풀림, 회차
 * 단위). 그래서 {@code schedule} 은 후자에서만 채워진다 — 전자는 어느 회차인지가 아니라
 * "회차가 생겼다"는 사실 자체가 알림이라 특정 회차가 없다.
 *
 * <p>{@code memberId} 를 연관관계가 아니라 값으로 든 건 {@link Wishlist}·{@link Reservation}
 * 과 같은 이유다 — 표를 맞춘다. {@code performance} 는 FK 를 건다. 수집 규칙에 안 맞는
 * 공연은 실제로 지워지므로(PerformanceSyncService.purgeNonPerformances) 참조가 남으면 안 된다.
 * {@code schedule} 도 같은 이유로 FK 를 건다 — 회차는 공연의 cascade 로 함께 지워진다.
 *
 * <p><b>중복 방지는 DB 가 최종 방어한다.</b> 동기화는 매일 도는데, 이력이 없으면 같은
 * 공연으로 매일 알림이 쌓인다 — 알림 기능이 스팸이 되는 가장 흔한 실패다. 그래서
 * (member_id, performance_id, type) 에 유니크 제약을 두고, 발송 쪽은 위반을 "이미 보냈음"
 * 으로 해석한다(WishlistService 가 중복 찜을 다루는 방식과 같다). SEAT_AVAILABLE 은 회차
 * 단위로 같은 뜻의 제약을 (member_id, schedule_id, type) 에 하나 더 둔다 — 자세한 이유는
 * V8 마이그레이션 주석 참고.
 *
 * <p>그 결과 한 공연당(또는 한 회차당) 한 사람에게 한 번만 간다. 8월 회차가 다 지난 뒤 9월
 * 회차가 열려도 다시 알리지 않는다 — 재알림의 가치보다 매일 같은 알림이 쌓이는 손해가 크다고
 * 봤다. 재알림이 필요해지면 유니크 키에 "어느 기간이 열렸는지"를 넣어 좁히면 된다.
 */
@Entity
@Table(
    name = "notification",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification", columnNames = {"member_id", "performance_id", "type"}),
        @UniqueConstraint(
            name = "uk_notification_schedule", columnNames = {"member_id", "schedule_id", "type"})
    },
    // 마이페이지 알림 목록은 "내 것을 최근 순으로"만 조회한다.
    indexes = @Index(name = "idx_notification_member", columnList = "member_id, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id")
    private Performance performance;

    /** SEAT_AVAILABLE 에서만 채워진다. SCHEDULE_OPENED 는 특정 회차가 없어 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private PerformanceSchedule schedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 읽은 시각. null 이면 안 읽음 — 별도 boolean 을 두지 않는 건 "언제 읽었는지"가 같이 남아서다. */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    public boolean isUnread() {
        return readAt == null;
    }

    /** 이미 읽은 알림을 다시 읽어도 최초 시각을 유지한다 — 목록을 다시 열 때마다 값이 바뀌면 안 된다. */
    public void markRead(LocalDateTime now) {
        if (readAt == null) {
            this.readAt = now;
        }
    }
}
