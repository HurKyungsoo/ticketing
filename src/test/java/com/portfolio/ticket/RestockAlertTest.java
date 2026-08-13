package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.NotificationService;
import com.portfolio.ticket.service.ReservationFacade;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.RestockAlertService;
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
 * 취소표 알림 — 매진 회차 구독 + 좌석 취소 시 알림.
 *
 * <p>SCHEDULE_OPENED(NotificationTest)와 대칭인 회차 단위 알림이다. 어려운 지점도 같다 —
 * 발송 자체보다 <b>중복·오발송 방지</b>다. 구독을 일회성으로 지우는 방식으로 막으므로,
 * 아래 테스트는 "정말 구독자에게만, 딱 한 번" 을 확인하는 데 집중한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RestockAlertTest {

    private static final Long MEMBER = 1L;
    private static final Long OTHER_MEMBER = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired RestockAlertService restockAlertService;
    @Autowired RestockSubscriptionRepository restockSubscriptionRepository;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationFacade reservationFacade;
    @Autowired ReservationService reservationService;

    private PerformanceSchedule schedule;
    private Long seatId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        restockSubscriptionRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("RESTOCK-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("취소표알림테스트공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(1)
                .basePrice(50_000)
                .build());

        schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(1)
                .remainingSeats(1)
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층")
                .rowNo(1)
                .seatNo(1)
                .grade(SeatGrade.VIP)
                .status(SeatStatus.AVAILABLE)
                .price(75_000)
                .build());
        seatId = seat.getId();
    }

    /** 좌석 하나를 선점하고 곧장 취소해, "그 회차에서 좌석이 풀렸다"를 재현한다. */
    private void holdThenCancel(Long byMemberId) {
        Reservation reservation = reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatId, byMemberId);
        // 결제 전(PENDING) 취소라 paymentKey 가 없어 토스 환불 호출을 안 탄다 — 테스트에 결제
        // 연동이 필요 없다(ReservationService.cancel 주석 참고).
        reservationService.cancel(reservation.getReservationNo(), byMemberId);
    }

    @DisplayName("구독한 회차에서 좌석이 취소되면 취소표 알림이 간다")
    @Test
    void notifiesWhenSeatReleased() {
        assertThat(restockAlertService.toggle(MEMBER, schedule.getId())).isTrue();

        holdThenCancel(OTHER_MEMBER);

        List<Notification> mine = notificationService.findMine(MEMBER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getType()).isEqualTo(NotificationType.SEAT_AVAILABLE);
        assertThat(mine.get(0).getSchedule().getId()).isEqualTo(schedule.getId());
        assertThat(mine.get(0).isUnread()).isTrue();
    }

    @DisplayName("구독하지 않은 사람에게는 취소표 알림이 가지 않는다")
    @Test
    void notifiesOnlySubscribers() {
        // MEMBER 는 구독 안 함
        holdThenCancel(OTHER_MEMBER);

        assertThat(notificationService.findMine(MEMBER)).isEmpty();
    }

    /**
     * 이 기능이 스팸이 되는 경로. 한 회차에서 취소가 잇달아 일어나도(같은 회원이 여러 좌석을
     * 골랐다가 전부 취소하는 등) 구독자는 딱 한 번만 알림을 받아야 한다.
     *
     * <p>구독을 지우는 방식으로 막으므로, 알림이 간 뒤에는 구독 자체가 사라져 있어야 한다.
     */
    @DisplayName("알림은 한 번만 가고, 보낸 뒤 구독은 지워진다")
    @Test
    void isOneShot() {
        restockAlertService.toggle(MEMBER, schedule.getId());
        assertThat(restockAlertService.isSubscribed(MEMBER, schedule.getId())).isTrue();

        holdThenCancel(OTHER_MEMBER);
        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
        assertThat(restockAlertService.isSubscribed(MEMBER, schedule.getId()))
                .as("한 번 알렸으면 구독은 지워져야 한다").isFalse();

        // 같은 회차에서 좌석이 또 풀려도(재선점 후 재취소) 구독이 없으니 알림도 없어야 한다.
        holdThenCancel(OTHER_MEMBER);
        assertThat(notificationService.findMine(MEMBER))
                .as("구독이 지워졌으니 두 번째 취소로는 알림이 늘지 않는다").hasSize(1);
    }

    @DisplayName("구독 토글은 호출 후의 상태를 돌려준다")
    @Test
    void toggleReturnsResultingState() {
        assertThat(restockAlertService.toggle(MEMBER, schedule.getId())).isTrue();
        assertThat(restockAlertService.isSubscribed(MEMBER, schedule.getId())).isTrue();

        assertThat(restockAlertService.toggle(MEMBER, schedule.getId())).isFalse();
        assertThat(restockAlertService.isSubscribed(MEMBER, schedule.getId())).isFalse();
    }

    @DisplayName("취소표 알림을 읽으면 그 회차의 좌석도로 보낸다 (공연 상세가 아니라)")
    @Test
    void readEndpointRedirectsToSeatMap() throws Exception {
        CustomUserDetails principal = principalFor("restock-read");
        restockAlertService.toggle(principal.getMemberId(), schedule.getId());
        holdThenCancel(OTHER_MEMBER);

        Long notificationId = notificationService.findMine(principal.getMemberId()).get(0).getId();

        mockMvc.perform(post("/mypage/notifications/{id}/read", notificationId)
                        .with(user(principal)).with(csrf()))
                .andExpect(redirectedUrl("/schedules/" + schedule.getId() + "/seats"));

        assertThat(notificationService.countUnread(principal.getMemberId())).isZero();
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
