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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 결제 페이지의 상태별 렌더링.
 *
 * <p>핵심은 <b>선점이 만료된 예매도 페이지가 떠야 한다</b>는 것이다. 만료되면
 * {@code HoldExpireScheduler} 가 좌석 연결을 끊어 예매의 좌석이 0개가 되는데,
 * 조회 쿼리가 {@code join fetch r.seats}(inner)면 그 예매를 아예 못 찾아 404 가 난다 —
 * 정작 "선점이 만료됐다"고 알려줘야 할 상황에서 아무것도 설명하지 못한다.
 *
 * <p>수동으로는 10분을 기다려야 재현되는 경로라 놓치기 쉽다. 그래서 여기서 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentPageTest {

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private CustomUserDetails owner;
    private PerformanceSchedule schedule;

    @BeforeEach
    void setUp() {
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        owner = new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId("payer").password("{noop}pw").nickname("payer")
                .role(MemberRole.USER).createdAt(LocalDateTime.now())
                .build()));

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("PAY-" + System.nanoTime())
                .title("결제 화면 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().plusDays(30))
                .endDate(LocalDate.now().plusDays(31))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());

        schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(10)
                .remainingSeats(10)
                .build());
    }

    /** 좌석 없이 예매만 만든다 — 만료 직후(좌석 해제됨) 상태를 그대로 흉내낸다. */
    private String saveReservation(ReservationStatus status, LocalDateTime holdExpiresAt) {
        String no = "TEST" + System.nanoTime() % 100_000_000L;
        reservationRepository.save(Reservation.builder()
                .reservationNo(no)
                .memberId(owner.getMemberId())
                .schedule(schedule)
                .seatSummary("1층 1열 1번 VIP")
                .status(status)
                .amount(75_000)
                .createdAt(LocalDateTime.now().minusMinutes(20))
                .holdExpiresAt(holdExpiresAt)
                .build());
        return no;
    }

    @DisplayName("선점이 만료된 예매도 결제 페이지가 열린다 (404 가 아니라 만료 안내)")
    @Test
    void expiredReservationStillRendersPage() throws Exception {
        String no = saveReservation(ReservationStatus.EXPIRED, LocalDateTime.now().minusMinutes(1));

        mockMvc.perform(get("/reservations/{no}/payment", no).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(view().name("reservation/payment"))
                .andExpect(model().attribute("payable", false))
                .andExpect(model().attribute("remainingSeconds", 0L));
    }

    @DisplayName("아직 유효한 예매는 결제 가능 상태 + 남은 시간이 양수")
    @Test
    void pendingReservationIsPayableWithRemainingTime() throws Exception {
        String no = saveReservation(ReservationStatus.PENDING, LocalDateTime.now().plusMinutes(9));

        mockMvc.perform(get("/reservations/{no}/payment", no).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("payable", true))
                .andExpect(result -> {
                    long remaining = (long) result.getModelAndView().getModel().get("remainingSeconds");
                    if (remaining <= 0 || remaining > 9 * 60) {
                        throw new AssertionError("남은 시간이 9분 안쪽이어야 한다: " + remaining);
                    }
                });
    }

    @DisplayName("시각은 지나지 않았어도 이미 결제된 예매는 결제 불가")
    @Test
    void confirmedReservationIsNotPayable() throws Exception {
        String no = saveReservation(ReservationStatus.CONFIRMED, LocalDateTime.now().plusMinutes(9));

        mockMvc.perform(get("/reservations/{no}/payment", no).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("payable", false));
    }

    @DisplayName("취소된 예매도 결제 불가 상태로 페이지가 열린다")
    @Test
    void canceledReservationIsNotPayable() throws Exception {
        String no = saveReservation(ReservationStatus.CANCELED, LocalDateTime.now().plusMinutes(9));

        mockMvc.perform(get("/reservations/{no}/payment", no).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("payable", false));
    }
}
