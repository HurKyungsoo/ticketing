package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.PerformanceSummaryView;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 무료 공연은 예매를 받지 않고 관람 방법을 안내한다.
 *
 * <p>종전에는 「전석무료」 공연도 가격을 못 읽은 공연처럼 기본 가격(40,000~75,000원)으로 좌석을 팔았다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreeAdmissionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ReservationFacade reservationFacade;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private CustomUserDetails member;

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

        member = new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId("freeuser").password("{noop}pw").nickname("freeuser")
                .role(MemberRole.USER).createdAt(LocalDateTime.now())
                .build()));
    }

    @DisplayName("기준가가 0원이고 원문에 금액이 없으면 무료, 금액이 하나라도 있으면 유료다")
    @Test
    void freeAdmissionRule() {
        assertThat(performance(0, "전석무료").isFreeAdmission()).isTrue();
        assertThat(performance(0, null).isFreeAdmission()).as("원문을 아직 못 받은 공연").isTrue();
        assertThat(performance(0, "어린이 무료, 일반 10,000원").isFreeAdmission()).isFalse();
        assertThat(performance(50_000, null).isFreeAdmission()).isFalse();
        assertThat(performance(null, null).isFreeAdmission()).as("가격 미상은 무료가 아니다").isFalse();
    }

    @DisplayName("무료 공연의 좌석은 선점할 수 없다 (화면을 우회해 API 를 직접 불러도)")
    @Test
    void cannotHoldSeatOfFreePerformance() {
        PerformanceSchedule schedule = scheduleWithSeat(performanceRepository.save(performance(0, "전석무료")));
        Long seatId = seatIdOf(schedule);

        assertThatThrownBy(() -> reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatId, member.getMemberId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("무료 공연은 예매를 받지 않습니다");
        assertThat(reservationRepository.findAll()).isEmpty();
    }

    @DisplayName("무료 공연은 상세에 일정과 관람 안내만 나오고, 좌석도로 가지 않는다")
    @Test
    void detailShowsGuideInsteadOfBooking() throws Exception {
        Performance performance = performanceRepository.save(performance(0, "전석무료"));
        PerformanceSchedule schedule = scheduleWithSeat(performance);

        String html = mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var summary = (PerformanceSummaryView) result.getModelAndView().getModel().get("summary");
                    assertThat(summary.lowestPrice()).as("좌석의 기본 가격이 아니라 무료").isZero();
                })
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("class=\"free-notice\"");
        assertThat(html).containsOnlyOnce("sd-chip sd-chip--info");
        assertThat(html).doesNotContain("/schedules/" + schedule.getId() + "/seats");
        assertThat(html).doesNotContain("75,000원");

        mockMvc.perform(get("/schedules/{id}/seats", schedule.getId()).with(user(member)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/performances/" + performance.getId()));
    }

    @DisplayName("유료 공연은 종전대로 좌석도로 간다")
    @Test
    void paidPerformanceUnchanged() throws Exception {
        Performance performance = performanceRepository.save(performance(0, "어린이 무료, 일반 10,000원"));
        PerformanceSchedule schedule = scheduleWithSeat(performance);

        String html = mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("class=\"free-notice\"");
        assertThat(html).contains("/schedules/" + schedule.getId() + "/seats");
    }

    private Performance performance(Integer basePrice, String priceGuidance) {
        Performance performance = Performance.builder()
                .externalId("FREE-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("무료 공연 테스트")
                .venue("테스트홀")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(5))
                .totalSeatCount(10)
                .basePrice(basePrice)
                .build();
        performance.updateGuidance(null, priceGuidance);
        return performance;
    }

    private PerformanceSchedule scheduleWithSeat(Performance performance) {
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(2))
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
        return seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId()).get(0).getId();
    }
}
