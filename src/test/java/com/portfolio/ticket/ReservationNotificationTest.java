package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * 예매 시각 기준으로 도는 알림 두 종류 — 공연 임박(D-1)과 관람평 요청.
 *
 * <p>{@link com.portfolio.ticket.service.NotificationService#createScheduleReminders}와
 * {@link com.portfolio.ticket.service.NotificationService#createReviewRequests}는 같은
 * 조회({@code findByStatusAndScheduleShowAtBetween})를 구간만 바꿔 쓴다. 그래서 이 테스트도
 * 어느 쪽이든 확인할 게 같다 — <b>경계(하루 단위로 정확히 그 날짜만)</b>와 <b>확정 건만</b>이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationNotificationTest {

    private static final Long MEMBER = 1L;
    private static final Long OTHER_MEMBER = 2L;

    /** now() 를 매번 부르면 자정 근처에서 날짜 경계가 흔들릴 수 있어 한 번 고정한다.
        서비스 메서드는 실제 시계가 아니라 이 값을 그대로 받아 계산하므로 흔들릴 일이 없다. */
    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final LocalDateTime TODAY_SHOW = NOW.toLocalDate().atTime(19, 30);
    private static final LocalDateTime TOMORROW_SHOW = NOW.toLocalDate().plusDays(1).atTime(19, 30);
    private static final LocalDateTime DAY_AFTER_TOMORROW_SHOW = NOW.toLocalDate().plusDays(2).atTime(19, 30);
    private static final LocalDateTime YESTERDAY_SHOW = NOW.toLocalDate().minusDays(1).atTime(19, 30);
    private static final LocalDateTime DAY_BEFORE_YESTERDAY_SHOW = NOW.toLocalDate().minusDays(2).atTime(19, 30);

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired WishlistRepository wishlistRepository;

    private Performance performance;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        reviewRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        performance = performanceRepository.save(Performance.builder()
                .externalId("RESNOTI-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("예매 알림 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());
    }

    /* ------------------------------------------------------------------
     *  공연 임박 알림 (D-1)
     * ------------------------------------------------------------------ */

    @DisplayName("내일 공연되는 확정 예매에 임박 알림이 간다")
    @Test
    void remindsTomorrowsConfirmedReservation() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, TOMORROW_SHOW);

        assertThat(notificationService.createScheduleReminders(NOW)).isEqualTo(1);

        List<Notification> mine = notificationService.findMine(MEMBER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getType()).isEqualTo(NotificationType.SCHEDULE_REMINDER);
    }

    /** 딱 내일만이다. 오늘은 이미 임박도 뭐도 아니고(당일), 모레는 아직 이르다. */
    @DisplayName("오늘·모레 공연은 임박 알림 대상이 아니다")
    @Test
    void remindsOnlyExactlyTomorrow() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, TODAY_SHOW);
        reservation(OTHER_MEMBER, ReservationStatus.CONFIRMED, DAY_AFTER_TOMORROW_SHOW);

        assertThat(notificationService.createScheduleReminders(NOW)).isZero();
    }

    @DisplayName("취소·만료·결제대기 건은 임박 알림을 받지 않는다")
    @Test
    void remindsOnlyConfirmed() {
        for (ReservationStatus status : new ReservationStatus[]{
                ReservationStatus.CANCELED, ReservationStatus.EXPIRED, ReservationStatus.PENDING}) {
            reservationRepository.deleteAll();
            scheduleRepository.deleteAll();
            reservation(MEMBER, status, TOMORROW_SHOW);

            assertThat(notificationService.createScheduleReminders(NOW))
                    .as("%s 상태로는 임박 알림이 가면 안 된다", status).isZero();
        }
    }

    @DisplayName("배치를 다시 돌려도 임박 알림이 중복되지 않는다")
    @Test
    void doesNotDuplicateReminders() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, TOMORROW_SHOW);

        notificationService.createScheduleReminders(NOW);
        notificationService.createScheduleReminders(NOW);

        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
    }

    /* ------------------------------------------------------------------
     *  관람평 요청 알림
     * ------------------------------------------------------------------ */

    @DisplayName("어제 공연이 지난 확정 예매에 관람평 요청 알림이 간다")
    @Test
    void requestsReviewForYesterdaysConfirmedReservation() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, YESTERDAY_SHOW);

        assertThat(notificationService.createReviewRequests(NOW)).isEqualTo(1);

        List<Notification> mine = notificationService.findMine(MEMBER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getType()).isEqualTo(NotificationType.REVIEW_REQUESTED);
    }

    /**
     * 관람 자격은 회차가 지나는 순간 생기므로(ReviewService.canWrite), 배치가 돌기 전에
     * 이미 관람평을 썼을 수 있다 — 그 사람에게 "관람평을 남겨보세요"를 또 보내면 이미 한
     * 일을 다시 요청하는 꼴이라 알림의 신뢰가 떨어진다.
     */
    @DisplayName("이미 관람평을 쓴 사람에게는 요청 알림이 가지 않는다")
    @Test
    void doesNotRequestReviewIfAlreadyWritten() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, YESTERDAY_SHOW);
        reviewRepository.save(Review.builder()
                .memberId(MEMBER).performance(performance).nickname("관람객")
                .rating(5).content("좋았습니다").createdAt(NOW).build());

        assertThat(notificationService.createReviewRequests(NOW)).isZero();
    }

    @DisplayName("그저께 지난 공연은 관람평 요청 대상이 아니다 (배치가 하루 늦게 도는 등 이미 지난 경우)")
    @Test
    void requestsOnlyExactlyYesterday() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, DAY_BEFORE_YESTERDAY_SHOW);

        assertThat(notificationService.createReviewRequests(NOW)).isZero();
    }

    @DisplayName("취소·만료·결제대기 건은 관람평 요청을 받지 않는다")
    @Test
    void requestsReviewOnlyForConfirmed() {
        for (ReservationStatus status : new ReservationStatus[]{
                ReservationStatus.CANCELED, ReservationStatus.EXPIRED, ReservationStatus.PENDING}) {
            reservationRepository.deleteAll();
            scheduleRepository.deleteAll();
            reservation(MEMBER, status, YESTERDAY_SHOW);

            assertThat(notificationService.createReviewRequests(NOW))
                    .as("%s 상태로는 관람평 요청이 가면 안 된다", status).isZero();
        }
    }

    @DisplayName("배치를 다시 돌려도 관람평 요청이 중복되지 않는다")
    @Test
    void doesNotDuplicateReviewRequests() {
        reservation(MEMBER, ReservationStatus.CONFIRMED, YESTERDAY_SHOW);

        notificationService.createReviewRequests(NOW);
        notificationService.createReviewRequests(NOW);

        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
    }

    /* ------------------------------------------------------------------
     *  읽으면 어디로 가는지 — 종류마다 다르다
     * ------------------------------------------------------------------ */

    /**
     * 나머지 알림은 공연 상세 맨 위로 가지만, 관람평 요청은 그걸 누른 이유(관람평을 쓰러 옴)
     * 까지 데려가야 한다 — 상세 페이지에 도착해서 또 관람평 칸을 찾아 스크롤하게 하면 안 된다.
     */
    @DisplayName("관람평 요청 알림을 읽으면 공연 상세의 관람평 칸(#reviews)까지 데려간다")
    @Test
    void readingReviewRequestGoesToReviewsAnchor() throws Exception {
        CustomUserDetails principal = principalFor("noti-review");
        reservation(principal.getMemberId(), ReservationStatus.CONFIRMED, YESTERDAY_SHOW);
        notificationService.createReviewRequests(NOW);
        Long notificationId = notificationService.findMine(principal.getMemberId()).get(0).getId();

        mockMvc.perform(post("/mypage/notifications/{id}/read", notificationId)
                        .with(user(principal)).with(csrf()))
                .andExpect(redirectedUrl("/performances/" + performance.getId() + "#reviews"));
    }

    private void reservation(Long memberId, ReservationStatus status, LocalDateTime showAt) {
        PerformanceSchedule schedule = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(performance.getId())
                .stream()
                .filter(s -> s.getShowAt().equals(showAt))
                .findFirst()
                .orElseGet(() -> scheduleRepository.save(PerformanceSchedule.builder()
                        .performance(performance)
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

    private CustomUserDetails principalFor(String loginId) {
        return new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId(loginId + "-" + System.nanoTime())
                .password("{noop}pw")
                .nickname(loginId)
                .role(MemberRole.USER)
                .createdAt(LocalDateTime.now())
                .build()));
    }
}
