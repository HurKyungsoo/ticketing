package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.ForbiddenException;
import com.portfolio.ticket.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관람평.
 *
 * <p><b>이 기능의 값어치는 저장이 아니라 자격 판정에 있다.</b> 예매 서비스에서 관람평이
 * 신뢰를 얻는 건 "그 공연을 실제로 본 사람이 썼다"는 전제 때문이고, 누구나 쓸 수 있으면
 * 그냥 댓글이다. 그래서 이 테스트는 대부분 <b>못 쓰는 경우</b>를 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReviewTest {

    private static final Long MEMBER = 1L;
    private static final Long OTHER = 2L;

    /**
     * 회차 시각을 초 단위로 끊어 고정한다. now() 를 매번 부르면 두 가지가 겹친다 —
     * 윈도우는 시계 해상도가 굵어 연달아 부른 값이 같아지고(회차 유니크 제약에 걸린다),
     * 반대로 나노초까지 다르면 DB 는 마이크로초로 잘라 저장해 조회한 값과 안 맞는다.
     */
    private static final LocalDateTime PAST = LocalDateTime.now().minusDays(1).withNano(0);
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(1).withNano(0);

    @Autowired ReviewService reviewService;
    @Autowired ReviewRepository reviewRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;

    private Performance performance;

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        performance = performanceRepository.save(Performance.builder()
                .externalId("REVIEW-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("관람평 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    /* ------------------------------------------------------------------
     *  쓸 수 있는 경우
     * ------------------------------------------------------------------ */

    @DisplayName("관람일이 지난 확정 예매가 있으면 쓸 수 있다")
    @Test
    void writesAfterAttending() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);

        Review review = reviewService.write(MEMBER, "관람객", performance.getId(), 5, "  무대가 좋았습니다  ");

        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getContent()).as("앞뒤 공백은 다듬어 저장한다").isEqualTo("무대가 좋았습니다");
        assertThat(review.getNickname()).isEqualTo("관람객");
    }

    /* ------------------------------------------------------------------
     *  못 쓰는 경우 — 여기가 이 기능의 핵심이다
     * ------------------------------------------------------------------ */

    @DisplayName("예매가 없으면 못 쓴다")
    @Test
    void rejectsWithoutReservation() {
        assertThatThrownBy(() -> reviewService.write(MEMBER, "낯선이", performance.getId(), 5, "좋아요"))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * 아직 안 본 공연의 관람평은 관람평이 아니다. 예매만 해두고 미리 별점을 매기는 걸 막는다.
     */
    @DisplayName("관람일이 아직 안 지났으면 못 쓴다")
    @Test
    void rejectsBeforeShowDate() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, FUTURE);

        assertThatThrownBy(() -> reviewService.write(MEMBER, "예매자", performance.getId(), 5, "기대돼요"))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * 취소분을 인정하면 "예매 → 관람평 → 취소"로 평점만 남기고 빠져나갈 수 있다.
     * 선점만 하고 결제 안 한 건(PENDING)도 같은 이유로 안 된다 — 돈을 낸 적이 없다.
     */
    @DisplayName("취소·만료·결제대기 건으로는 못 쓴다")
    @Test
    void rejectsNonConfirmedReservations() {
        for (ReservationStatus status : new ReservationStatus[]{
                ReservationStatus.CANCELED, ReservationStatus.EXPIRED, ReservationStatus.PENDING}) {
            reviewRepository.deleteAll();
            reservationRepository.deleteAll();
            reservation(MEMBER, status, PAST);

            assertThatThrownBy(() -> reviewService.write(MEMBER, "관람객", performance.getId(), 5, "좋아요"))
                    .as("%s 상태로는 쓸 수 없어야 한다", status)
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @DisplayName("남의 공연 예매로는 이 공연에 못 쓴다")
    @Test
    void rejectsReservationForAnotherPerformance() {
        Performance other = performanceRepository.save(Performance.builder()
                .externalId("REVIEW-OTHER-" + System.nanoTime())
                .sourceType(SourceType.KOPIS).title("다른 공연")
                .category(PerformanceCategory.MUSICAL).venue("테스트홀").region("서울특별시")
                .startDate(LocalDate.now().minusDays(10)).endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(100).basePrice(50_000).build());
        reservationFor(other, MEMBER, ReservationStatus.CONFIRMED, PAST);

        assertThatThrownBy(() -> reviewService.write(MEMBER, "관람객", performance.getId(), 5, "좋아요"))
                .isInstanceOf(ForbiddenException.class);
    }

    /* ------------------------------------------------------------------
     *  값 검증 — 평균을 조용히 망가뜨리는 것들
     * ------------------------------------------------------------------ */

    @DisplayName("별점이 1~5 를 벗어나면 거부한다")
    @Test
    void rejectsRatingOutOfRange() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);

        for (int bad : new int[]{0, 6, -1, 100}) {
            assertThatThrownBy(() -> reviewService.write(MEMBER, "관람객", performance.getId(), bad, "좋아요"))
                    .as("별점 %d", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @DisplayName("내용이 비어 있으면 거부한다")
    @Test
    void rejectsBlankContent() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);

        assertThatThrownBy(() -> reviewService.write(MEMBER, "관람객", performance.getId(), 5, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ------------------------------------------------------------------
     *  한 사람 하나 · 집계
     * ------------------------------------------------------------------ */

    /** 여러 개를 허용하면 한 사람이 평점을 여러 번 반영해 평균을 밀 수 있다. */
    @DisplayName("같은 공연에 두 번 쓰면 새로 쌓이지 않고 고쳐진다")
    @Test
    void secondWriteEditsInsteadOfAdding() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);

        reviewService.write(MEMBER, "관람객", performance.getId(), 5, "처음 인상");
        reviewService.write(MEMBER, "관람객", performance.getId(), 3, "다시 보니 이렇다");

        assertThat(reviewRepository.count()).isEqualTo(1);
        assertThat(reviewService.stat(performance.getId()).getAverage()).isEqualTo(3.0);
    }

    @DisplayName("평균과 개수를 집계한다")
    @Test
    void aggregatesRatings() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);
        reservation(OTHER, ReservationStatus.CONFIRMED, PAST);
        reviewService.write(MEMBER, "가", performance.getId(), 5, "최고");
        reviewService.write(OTHER, "나", performance.getId(), 2, "글쎄");

        var stat = reviewService.stat(performance.getId());
        assertThat(stat.getCount()).isEqualTo(2);
        assertThat(stat.getAverage()).isEqualTo(3.5);
    }

    /**
     * 관람평이 없을 때 평균은 0.0 이 아니라 null 이다. "아직 평이 없다"와 "평균이 0 이다"는
     * 다른 말이고, 화면은 전자일 때 줄을 통째로 숨긴다.
     */
    @DisplayName("관람평이 없으면 개수 0 · 평균 null")
    @Test
    void emptyStatHasNullAverage() {
        var stat = reviewService.stat(performance.getId());

        assertThat(stat.getCount()).isZero();
        assertThat(stat.getAverage()).isNull();
    }

    @DisplayName("내 관람평만 지울 수 있다")
    @Test
    void deletesOnlyOwn() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, PAST);
        Review mine = reviewService.write(MEMBER, "관람객", performance.getId(), 5, "좋아요");

        reviewService.delete(mine.getId(), OTHER);
        assertThat(reviewRepository.count()).as("남이 눌러도 안 지워진다").isEqualTo(1);

        reviewService.delete(mine.getId(), MEMBER);
        assertThat(reviewRepository.count()).isZero();
    }

    /* ------------------------------------------------------------------
     *  헬퍼
     * ------------------------------------------------------------------ */

    private void reservation(Long memberId, ReservationStatus status, LocalDateTime showAt) {
        reservationFor(performance, memberId, status, showAt);
    }

    /**
     * 회차는 (공연, 시각)에 유니크 제약이 있다 — 같은 회차를 두 사람이 예매하는 건 정상이므로
     * 있으면 그대로 쓰고 없을 때만 만든다. 매번 새로 만들면 두 번째 예매에서 제약에 걸린다.
     */
    private void reservationFor(Performance target, Long memberId, ReservationStatus status, LocalDateTime showAt) {
        PerformanceSchedule schedule = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(target.getId())
                .stream()
                .filter(s -> s.getShowAt().equals(showAt))
                .findFirst()
                .orElseGet(() -> scheduleRepository.save(PerformanceSchedule.builder()
                        .performance(target)
                        .showAt(showAt)
                        .totalSeats(10).remainingSeats(10)
                        .build()));
        reservationRepository.save(Reservation.builder()
                .reservationNo("RV" + System.nanoTime() % 100_000_000L)
                .memberId(memberId)
                .schedule(schedule)
                .seatSummary("1층 1열 1번 VIP")
                .status(status)
                .amount(50_000)
                .createdAt(LocalDateTime.now())
                .holdExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
    }
}
