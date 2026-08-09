package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관람평.
 *
 * <p><b>아무나 못 쓴다.</b> 그 공연을 실제로 본 사람만 — 확정된 예매가 있고 관람일이 지나야
 * 한다(ReviewService.write). "누구나 작성"은 실서비스 관례에 안 맞고, 예매 서비스에서
 * 관람평의 값어치는 "본 사람이 썼다"는 데서 나온다.
 *
 * <p>{@code memberId} 를 연관관계가 아니라 값으로 든 건 {@link Wishlist}·{@link Reservation}·
 * {@link Notification} 과 같은 이유다 — 표를 맞춘다. {@code performance} 는 FK 를 건다.
 * 수집 규칙에 안 맞는 공연은 실제로 지워지므로(PerformanceSyncService.purgeNonPerformances)
 * 참조가 남으면 안 된다.
 *
 * <p><b>한 공연에 한 사람이 하나만.</b> 같은 공연을 두 번 봤어도 관람평은 하나다 —
 * 여러 개를 허용하면 한 사람이 평점을 여러 번 반영해 평균을 밀 수 있다.
 * 유니크 제약이 최종 방어이고, 서비스는 그 위반을 "이미 썼음"으로 해석한다(찜과 같은 방식).
 */
@Entity
@Table(
    name = "review",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_review", columnNames = {"member_id", "performance_id"}),
    // 공연 상세가 "이 공연의 관람평을 최근 순으로" 읽는다.
    indexes = @Index(name = "idx_review_performance", columnList = "performance_id, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Review {

    /** 별점 범위. 화면·서버·DB 세 곳이 같은 값을 봐야 해서 여기 한 번만 적는다. */
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    /** 본문 최대 길이. DB 컬럼과 같은 값이어야 한다(V7 마이그레이션). */
    public static final int MAX_CONTENT = 500;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id")
    private Performance performance;

    /** 작성 시점의 닉네임 스냅샷. 닉네임이 바뀌어도 그때 쓴 이름으로 남는다 —
     *  회원을 조인하지 않고 목록을 그릴 수 있게 하는 목적도 겸한다. */
    @Column(nullable = false, length = 20)
    private String nickname;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = MAX_CONTENT)
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 수정. 별점과 본문만 바뀐다 — 작성 시각은 그대로 둔다(언제 본 공연인지의 단서다). */
    public void edit(int rating, String content) {
        this.rating = rating;
        this.content = content;
    }

    public boolean isWrittenBy(Long memberId) {
        return this.memberId.equals(memberId);
    }
}
