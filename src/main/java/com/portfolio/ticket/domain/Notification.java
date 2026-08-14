package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 사용자에게 보여줄 알림 한 건. 종류는 {@link NotificationType} 네 가지 — 찜한 공연 회차
 * 오픈, 구독한(매진) 회차의 좌석 풀림, 확정 예매 임박(D-1), 관람평 요청.
 *
 * <p>{@code schedule} 은 {@link NotificationType#SEAT_AVAILABLE} 에서만 채워진다 —
 * {@link NotificationType#SCHEDULE_OPENED} 는 어느 회차인지가 아니라 "회차가 생겼다"는
 * 사실 자체가 알림이라 특정 회차가 없다.
 *
 * <p>{@code memberId} 를 연관관계가 아니라 값으로 든 건 {@link Wishlist}·{@link Reservation}
 * 과 같은 이유다 — 표를 맞춘다. {@code performance} 는 FK 를 건다. 수집 규칙에 안 맞는
 * 공연은 실제로 지워지므로(PerformanceSyncService.purgeNonPerformances) 참조가 남으면 안 된다.
 * {@code schedule} 도 같은 이유로 FK 를 건다 — 회차는 공연의 cascade 로 함께 지워진다.
 *
 * <p><b>중복 방지는 DB 가 최종 방어한다.</b> 회차 오픈 배치도, 임박·관람평 배치도 매일 도는데,
 * 이력이 없으면 같은 공연으로 매일 알림이 쌓인다 — 알림 기능이 스팸이 되는 가장 흔한 실패다.
 * 그래서 (member_id, performance_id, type) 에 유니크 제약을 두고, 발송 쪽은 위반을
 * "이미 보냈음"으로 해석한다(WishlistService 가 중복 찜을 다루는 방식과 같다). SEAT_AVAILABLE 은
 * 회차 단위로 같은 뜻의 제약을 (member_id, schedule_id, type) 에 하나 더 둔다 — 자세한 이유는
 * V8 마이그레이션 주석 참고.
 *
 * <p>그 결과 <b>한 공연당(또는 한 회차당) 한 사람에게 한 종류는 한 번만</b> 간다. SCHEDULE_OPENED 는
 * 8월 회차가 다 지난 뒤 9월 회차가 열려도 다시 알리지 않는다 — 재알림의 가치보다 매일 같은
 * 알림이 쌓이는 손해가 크다고 봤다. SCHEDULE_REMINDER 도 같은 키를 쓰므로, 같은 공연을
 * 날짜만 다르게 두 번 예매한 사람은 먼저 돌아오는 회차의 임박 알림만 받는다 — 흔치 않은
 * 경우고, 회차 단위로 다시 알리려면 별도 컬럼(schedule 참조)이 필요해 지금은 그렇게까지
 * 하지 않았다. 재알림이 필요해지면 유니크 키에 "어느 회차인지"를 넣어 좁히면 된다.
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

    /** 목록 화면의 회차 시각 표기. 상세 화면(SeoView.SHOW_AT)과 같은 패턴이다. */
    private static final DateTimeFormatter SHOW_AT_FORMAT =
            DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN);

    /**
     * 목록 화면 문구. 공연명(볼드) 바로 뒤에 이어 붙는다.
     *
     * <p>SCHEDULE_OPENED 만 조사("의")로 바로 시작한다 — "의"는 앞말의 받침 유무와 무관하게
     * 형태가 안 바뀌는 몇 안 되는 조사라 안전하다. 나머지는 일부러 조사로 시작하지 않는다.
     * "이/가"("공연이 내일입니다")나 "은/는"("관람은 어떠셨나요")은 공연명 마지막 글자의
     * 받침에 따라 형태가 갈리는데, 실제 공연명은 "(10.04)"처럼 기호로 끝나는 경우가 흔해서
     * 마지막 글자만 보고는 받침을 제대로 못 가린다. 그래서 조사를 "공연"·"관람" 같은
     * 고정 명사 뒤로 옮겨, 공연명이 무엇으로 끝나든 문장이 안 깨지게 했다. SEAT_AVAILABLE 은
     * 조사 대신 회차 시각으로 시작해 "그 회차"에 자리가 났다는 걸 분명히 한다.
     */
    public String messageSuffix() {
        return switch (type) {
            case SCHEDULE_OPENED -> "의 예매 가능한 회차가 열렸습니다.";
            case SEAT_AVAILABLE -> " " + schedule.getShowAt().format(SHOW_AT_FORMAT) + "회차에 자리가 났습니다.";
            case SCHEDULE_REMINDER -> " 공연이 내일입니다.";
            case REVIEW_REQUESTED -> " 관람은 어떠셨나요? 관람평을 남겨보세요.";
        };
    }

    /** 목록 화면의 행 끝 버튼 문구. */
    public String actionLabel() {
        return switch (type) {
            case SCHEDULE_OPENED, SEAT_AVAILABLE -> "예매하기";
            case SCHEDULE_REMINDER -> "예매 확인";
            case REVIEW_REQUESTED -> "후기 남기기";
        };
    }
}
