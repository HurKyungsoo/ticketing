package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이미 시작된(지난) 회차 처리.
 *
 * <p>종전에는 <b>예매 경로 어디에도 공연 시각 검증이 없었다.</b> 기간이 여러 날인 공연은
 * 지난 회차가 상세 목록에 그대로 떴고, 눌러서 좌석도로 들어가 선점·결제까지 갈 수 있었다 —
 * 이미 끝난 공연의 티켓을 파는 셈이다. 게다가 그렇게 산 표는 {@code refundFeeRate} 가
 * "공연 당일에는 취소할 수 없습니다"로 막아 환불도 안 된다.
 *
 * <p>화면에서 감추는 것만으로는 부족하다 — 좌석도 URL 과 선점 API 는 회차 id 만 있으면
 * 그대로 호출된다. 그래서 서버(ReservationService)에서 막는 걸 가장 먼저 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PastScheduleTest {

    @Autowired MockMvc mockMvc;
    @Autowired ReservationFacade reservationFacade;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private CustomUserDetails member;
    private Performance performance;

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

        member = new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId("pastuser").password("{noop}pw").nickname("pastuser")
                .role(MemberRole.USER).createdAt(LocalDateTime.now())
                .build()));

        performance = performanceRepository.save(Performance.builder()
                .externalId("PAST-" + System.nanoTime())
                .title("지난 회차 테스트 공연")
                .venue("테스트홀")
                // 기간은 이미 시작됐고 아직 안 끝난 공연 — 지난 회차와 남은 회차가 섞인다.
                .startDate(LocalDate.now().minusDays(3))
                .endDate(LocalDate.now().plusDays(3))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());
    }

    /** 좌석 1개를 가진 회차를 만든다. */
    private PerformanceSchedule scheduleWithSeat(LocalDateTime showAt) {
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt)
                .totalSeats(10)
                .remainingSeats(10)
                .build());
        seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층").rowNo(1).seatNo(1)
                .grade(SeatGrade.VIP).status(SeatStatus.AVAILABLE).price(75_000)
                .build());
        return schedule;
    }

    private Long seatIdOf(PerformanceSchedule schedule) {
        return seatRepository.findAll().stream()
                .filter(s -> s.getSchedule().getId().equals(schedule.getId()))
                .findFirst().orElseThrow().getId();
    }

    /* ------------------------------------------------------------------
     *  서버 강제 — 이게 핵심이다
     * ------------------------------------------------------------------ */

    @DisplayName("지난 회차는 좌석 선점이 거부된다 (화면을 우회해 API 를 직접 불러도)")
    @Test
    void cannotHoldSeatOfPastSchedule() {
        PerformanceSchedule past = scheduleWithSeat(LocalDateTime.now().minusHours(1));
        Long seatId = seatIdOf(past);

        assertThatThrownBy(() ->
                reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatId, member.getMemberId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 시작된 공연");

        assertThat(reservationRepository.findAll()).as("예매가 만들어지면 안 된다").isEmpty();
    }

    @DisplayName("다중 좌석 선점도 같은 지점에서 막힌다")
    @Test
    void cannotHoldMultipleSeatsOfPastSchedule() {
        PerformanceSchedule past = scheduleWithSeat(LocalDateTime.now().minusMinutes(5));
        Long seatId = seatIdOf(past);

        assertThatThrownBy(() ->
                reservationFacade.holdMultiple(HoldStrategy.PESSIMISTIC, List.of(seatId), member.getMemberId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 시작된 공연");
    }

    @DisplayName("아직 안 지난 회차는 그대로 선점된다 — 막는 조건이 과하지 않은지")
    @Test
    void upcomingScheduleStillBookable() {
        PerformanceSchedule upcoming = scheduleWithSeat(LocalDateTime.now().plusDays(1));
        Long seatId = seatIdOf(upcoming);

        Reservation reservation =
                reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatId, member.getMemberId());

        assertThat(reservation.getReservationNo()).isNotBlank();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    /* ------------------------------------------------------------------
     *  화면
     * ------------------------------------------------------------------ */

    @DisplayName("상세의 회차 목록에서 지난 회차는 빠지고 남은 회차만 보인다")
    @Test
    void detailListsOnlyUpcomingSchedules() throws Exception {
        scheduleWithSeat(LocalDateTime.now().minusDays(2));
        scheduleWithSeat(LocalDateTime.now().minusHours(2));
        PerformanceSchedule upcoming = scheduleWithSeat(LocalDateTime.now().plusDays(1));

        mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var days = (List<?>) result.getModelAndView().getModel().get("scheduleDays");
                    if (days.size() != 1) {
                        throw new AssertionError("남은 회차 1건만 나와야 한다: " + days.size());
                    }
                });

        // 남은 회차의 좌석도 링크는 그대로 열린다.
        mockMvc.perform(get("/schedules/{id}/seats", upcoming.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("reservation/seat-map"));
    }

    @DisplayName("지난 회차 좌석도 URL 로 직접 들어가면 공연 상세로 되돌린다 (404 가 아니라)")
    @Test
    void pastSeatMapRedirectsToDetail() throws Exception {
        PerformanceSchedule past = scheduleWithSeat(LocalDateTime.now().minusHours(3));

        mockMvc.perform(get("/schedules/{id}/seats", past.getId()).with(user(member)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/performances/" + performance.getId()));
    }

    @DisplayName("회차가 전부 지났으면 목록이 비고 안내가 나온다")
    @Test
    void allPastSchedulesLeaveEmptyList() throws Exception {
        scheduleWithSeat(LocalDateTime.now().minusDays(2));
        scheduleWithSeat(LocalDateTime.now().minusHours(1));

        mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var days = (List<?>) result.getModelAndView().getModel().get("scheduleDays");
                    if (!days.isEmpty()) {
                        throw new AssertionError("회차 목록이 비어야 한다: " + days.size());
                    }
                });
    }
}
