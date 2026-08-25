package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이슈 #2. 예매 후 관리 강화 — 마이페이지 예매내역에서 "취소하면 얼마가 남는지"를
 * 버튼을 누르기 전에 보여주고(종전엔 취소를 실행해야만, 즉 되돌릴 수 없게 된 다음에야
 * 알 수 있었다), 공연 상세·길찾기로 돌아갈 길을 붙인다(종전엔 예매내역에서 그 공연을
 * 다시 찾으려면 검색부터 다시 해야 했다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationManagementTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Long memberId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        memberId = memberRepository.save(Member.builder()
                .loginId("resmgmt")
                .password(passwordEncoder.encode("password"))
                .nickname("관리테스트")
                .email("resmgmt@example.com")
                .createdAt(LocalDateTime.now())
                .build()).getId();
    }

    private CustomUserDetails principal() {
        return new CustomUserDetails(memberRepository.findByLoginId("resmgmt").orElseThrow());
    }

    private PerformanceSchedule scheduleAt(LocalDateTime showAt, Double lat, Double lng) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("RESMGMT-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("관리테스트공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .latitude(lat)
                .longitude(lng)
                .startDate(LocalDate.now().minusDays(5))
                .endDate(LocalDate.now().plusDays(60))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        return scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt.withNano(0))
                .totalSeats(100)
                .remainingSeats(100)
                .build());
    }

    private Reservation confirmedReservation(PerformanceSchedule schedule, int amount) {
        LocalDateTime now = LocalDateTime.now();
        return reservationRepository.save(Reservation.builder()
                .reservationNo("R" + System.nanoTime())
                .memberId(memberId)
                .schedule(schedule)
                .seatSummary("1층 1열 1번 VIP")
                .status(ReservationStatus.CONFIRMED)
                .amount(amount)
                .createdAt(now)
                .holdExpiresAt(now.plusMinutes(10))
                .confirmedAt(now)
                .paymentKey("test-payment-key")
                .build());
    }

    @DisplayName("10일 이상 남았으면 수수료 없이 전액 환불된다")
    @Test
    void noFeeTenDaysOrMoreBefore() {
        // plusDays(10) 에 withHour 로 시각을 맞추면, 지금 시각이 그 시(hour)보다 늦은 채로
        // 테스트가 돌 때 실제 간격이 10일에서 몇 시간 모자라져 toDays() 가 9로 잘리고
        // 경계(>=10) 를 못 넘어 7일 구간(10%)으로 떨어질 수 있다. 여유를 더 둔다.
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().plusDays(11), null, null);
        Reservation r = confirmedReservation(schedule, 50_000);

        Reservation.RefundPreview preview = r.refundPreview(LocalDateTime.now());

        assertThat(preview.cancelable()).isTrue();
        assertThat(preview.feeRate()).isEqualTo(0);
        assertThat(preview.refundAmount()).isEqualTo(50_000);
    }

    @DisplayName("2일 남았으면 30% 수수료를 뗀 금액이 환불된다")
    @Test
    void feeAppliesInLowerTier() {
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().plusDays(2).withHour(19), null, null);
        Reservation r = confirmedReservation(schedule, 50_000);

        Reservation.RefundPreview preview = r.refundPreview(LocalDateTime.now());

        assertThat(preview.cancelable()).isTrue();
        assertThat(preview.feeRate()).isEqualTo(30);
        assertThat(preview.refundAmount()).isEqualTo(35_000);
    }

    @DisplayName("당일이면 취소할 수 없다 - 예외 대신 cancelable=false 로 돌아온다")
    @Test
    void notCancelableOnShowDay() {
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().withHour(23).withMinute(59), null, null);
        Reservation r = confirmedReservation(schedule, 50_000);

        Reservation.RefundPreview preview = r.refundPreview(LocalDateTime.now());

        assertThat(preview.cancelable()).isFalse();
    }

    @DisplayName("결제 대기 건은 결제 전이라 수수료 없이 전액이다")
    @Test
    void pendingReservationHasNoFee() {
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().withHour(23).withMinute(59), null, null);
        LocalDateTime now = LocalDateTime.now();
        Reservation r = reservationRepository.save(Reservation.builder()
                .reservationNo("R" + System.nanoTime())
                .memberId(memberId)
                .schedule(schedule)
                .seatSummary("1층 1열 1번 VIP")
                .status(ReservationStatus.PENDING)
                .amount(50_000)
                .createdAt(now)
                .holdExpiresAt(now.plusMinutes(10))
                .build());

        Reservation.RefundPreview preview = r.refundPreview(now);

        assertThat(preview.cancelable()).isTrue();
        assertThat(preview.feeRate()).isEqualTo(0);
        assertThat(preview.refundAmount()).isEqualTo(50_000);
    }

    @DisplayName("마이페이지에 수수료·환불 예상 안내가 실제로 렌더링된다")
    @Test
    void refundHintRendersOnPage() throws Exception {
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().plusDays(2).withHour(19), null, null);
        confirmedReservation(schedule, 50_000);

        mockMvc.perform(get("/mypage/reservations").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("지금 취소하면 수수료 30% · 환불 예상 35,000원");
                });
    }

    @DisplayName("공연 상세 링크가 예매 건마다 실제 공연 id로 걸린다")
    @Test
    void detailLinkPointsToThePerformance() throws Exception {
        PerformanceSchedule schedule = scheduleAt(LocalDateTime.now().plusDays(10).withHour(19), null, null);
        confirmedReservation(schedule, 50_000);
        Long performanceId = schedule.getPerformance().getId();

        mockMvc.perform(get("/mypage/reservations").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("href=\"/performances/" + performanceId + "\"");
                });
    }

    @DisplayName("좌표가 있으면 길찾기, 없으면 지도에서 찾기 링크가 뜬다")
    @Test
    void directionsLinkDependsOnCoordinates() throws Exception {
        PerformanceSchedule withCoords = scheduleAt(LocalDateTime.now().plusDays(10).withHour(19), 37.5, 127.0);
        confirmedReservation(withCoords, 50_000);

        mockMvc.perform(get("/mypage/reservations").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("map.naver.com/p/directions/-/127.0,37.5,");
                    assertThat(html).contains("길찾기");
                });

        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        PerformanceSchedule withoutCoords = scheduleAt(LocalDateTime.now().plusDays(10).withHour(19), null, null);
        confirmedReservation(withoutCoords, 50_000);

        mockMvc.perform(get("/mypage/reservations").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("map.naver.com/p/search/");
                    assertThat(html).contains("지도에서 찾기");
                });
    }
}
