package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.PastSeatCleanup;
import com.portfolio.ticket.service.ReservationFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 지난 회차의 빈 좌석만 지우고, 예매와 이어진 좌석·회차·앞으로의 회차는 남기는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
class PastSeatCleanupTest {

    @Autowired PastSeatCleanup cleanup;
    @Autowired ReservationFacade reservationFacade;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired RestockSubscriptionRepository restockSubscriptionRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Performance performance;
    private CustomUserDetails member;

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
        memberRepository.deleteAll();

        member = new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId("cleanuser").password("{noop}pw").nickname("cleanuser")
                .role(MemberRole.USER).createdAt(LocalDateTime.now())
                .build()));
        performance = performanceRepository.save(Performance.builder()
                .externalId("CLEAN-" + System.nanoTime())
                .title("좌석 정리 테스트")
                .venue("테스트홀")
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(3)
                .basePrice(50_000)
                .build());
    }

    @Test
    void 지난_지_하루가_넘은_회차의_빈_좌석만_지운다() {
        PerformanceSchedule old = scheduleWithSeats(LocalDateTime.now().minusDays(3), 3);
        PerformanceSchedule recent = scheduleWithSeats(LocalDateTime.now().minusHours(6), 3);
        PerformanceSchedule upcoming = scheduleWithSeats(LocalDateTime.now().plusDays(2), 3);

        PastSeatCleanup.Result result = cleanup.run();

        assertThat(result.seats()).isEqualTo(3);
        assertThat(result.finished()).isTrue();
        assertThat(seatsOf(old)).isEmpty();
        assertThat(scheduleRepository.findById(old.getId())).as("회차 행은 남긴다").isPresent();
        assertThat(seatsOf(recent)).as("지난 지 하루가 안 된 회차는 남긴다").hasSize(3);
        assertThat(seatsOf(upcoming)).hasSize(3);
    }

    @Test
    void 예매와_이어진_좌석은_지난_회차여도_남긴다() {
        PerformanceSchedule schedule = scheduleWithSeats(LocalDateTime.now().plusDays(1), 3);
        Reservation reservation = reservationFacade.hold(
                HoldStrategy.PESSIMISTIC, seatsOf(schedule).get(0).getId(), member.getMemberId());
        // 공연이 지나간 상태로 만든다.
        moveTo(schedule, LocalDateTime.now().minusDays(3));

        cleanup.run();

        List<Seat> remaining = seatsOf(schedule);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getStatus()).isNotEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationRepository.findById(reservation.getId())).isPresent();
    }

    @Test
    void 지울_것이_없으면_아무것도_하지_않는다() {
        scheduleWithSeats(LocalDateTime.now().plusDays(1), 3);

        PastSeatCleanup.Result result = cleanup.run();

        assertThat(result.schedules()).isZero();
        assertThat(result.finished()).isTrue();
    }

    private PerformanceSchedule scheduleWithSeats(LocalDateTime showAt, int count) {
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt)
                .totalSeats(count)
                .remainingSeats(count)
                .build());
        for (int i = 1; i <= count; i++) {
            seatRepository.save(Seat.builder()
                    .schedule(schedule)
                    .section("A").rowNo(1).seatNo(i)
                    .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000)
                    .build());
        }
        return schedule;
    }

    /** 예매가 걸린 회차를 지난 회차로 옮긴다. 지난 회차는 예매가 막혀 있어 처음부터 만들 수 없다. */
    private void moveTo(PerformanceSchedule schedule, LocalDateTime showAt) {
        jdbcTemplate.update("update performance_schedule set show_at = ? where id = ?", showAt, schedule.getId());
    }

    private List<Seat> seatsOf(PerformanceSchedule schedule) {
        return seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId());
    }
}
