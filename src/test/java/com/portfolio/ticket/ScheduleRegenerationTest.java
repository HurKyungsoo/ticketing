package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
import com.portfolio.ticket.service.ScheduleRegeneration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 규칙으로 만든 회차·좌석을 저장된 KOPIS 원문으로 다시 만들기.
 *
 * <p>사용자 기록이 걸린 회차를 지우지 않는지, 그리고 재생성과 좌석 선점이 동시에 일어나도
 * 회차 없는 예매가 생기거나 서로를 기다리며 멈추지 않는지를 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleRegenerationTest {

    private static final String SHOW_TIMES = "토요일(15:00,19:00)";
    private static final String PRICES = "R석 60,000원, S석 40,000원";

    @Autowired ScheduleRegeneration regeneration;
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

    private CustomUserDetails member;
    private LocalDate firstSaturday;

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
                .loginId("regenuser").password("{noop}pw").nickname("regenuser")
                .role(MemberRole.USER).createdAt(LocalDateTime.now())
                .build()));
        firstSaturday = LocalDate.now().plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }

    @Test
    void 손대지_않은_내일_이후_회차를_원문의_요일과_시각으로_바꾸고_좌석은_원문_가격으로_만든다() {
        Performance performance = performance(SHOW_TIMES, firstSaturday.plusDays(7));
        PerformanceSchedule today = schedule(performance, LocalDate.now().atTime(23, 30));
        PerformanceSchedule held = schedule(performance, LocalDate.now().plusDays(1).atTime(11, 0));
        for (LocalDate d = LocalDate.now().plusDays(2); !d.isAfter(performance.getEndDate()); d = d.plusDays(1)) {
            schedule(performance, d.atTime(11, 0));
        }
        reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatIdOf(held), member.getMemberId());

        ScheduleRegeneration.Result result = regeneration.run();

        assertThat(result.performances()).isEqualTo(1);
        assertThat(scheduleRepository.findById(today.getId())).as("오늘 회차는 두지 않는다").isPresent();
        assertThat(scheduleRepository.findById(held.getId())).as("선점이 걸린 회차는 남긴다").isPresent();

        List<LocalDateTime> after = showTimesOf(performance);
        List<LocalDateTime> expected = List.of(
                firstSaturday.atTime(15, 0), firstSaturday.atTime(19, 0),
                firstSaturday.plusDays(7).atTime(15, 0), firstSaturday.plusDays(7).atTime(19, 0));
        assertThat(after).containsAll(expected);
        assertThat(after).as("손대지 않은 기본 회차는 없어진다")
                .filteredOn(t -> t.toLocalDate().isAfter(LocalDate.now().plusDays(1)) && t.toLocalTime().equals(LocalTime.of(11, 0)))
                .isEmpty();

        PerformanceSchedule saturday = scheduleRepository.findAll().stream()
                .filter(s -> s.getShowAt().equals(firstSaturday.atTime(15, 0))).findFirst().orElseThrow();
        assertThat(seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(saturday.getId()))
                .isNotEmpty()
                .extracting(Seat::getPrice).contains(60_000, 40_000);

        assertThat(performanceRepository.findById(performance.getId()).orElseThrow().getSchedulesRebuiltOn())
                .isEqualTo(LocalDate.now());
        assertThat(regeneration.run().performances()).as("다시 만든 공연은 다시 고르지 않는다").isZero();
    }

    @Test
    void 좌석이_많은_큰_공연장_회차도_지우고_다시_만든다() {
        // 운영에서 회차당 1,500석인 공연의 재생성이 StackOverflowError 로 건너뛰어졌다.
        Performance performance = performance(SHOW_TIMES, firstSaturday);
        PerformanceSchedule big = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDate.now().plusDays(2).atTime(11, 0))
                .totalSeats(1_500)
                .remainingSeats(1_500)
                .build());
        List<Seat> seats = new java.util.ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            seats.add(Seat.builder()
                    .schedule(big)
                    .section("1층").rowNo(i / 30 + 1).seatNo(i % 30 + 1)
                    .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000)
                    .build());
        }
        seatRepository.saveAll(seats);

        ScheduleRegeneration.Result result = regeneration.run();

        assertThat(result.performances()).isEqualTo(1);
        assertThat(scheduleRepository.findById(big.getId())).isEmpty();
        assertThat(seatRepository.countByScheduleIdAndStatus(big.getId(), SeatStatus.AVAILABLE)).isZero();
        assertThat(performanceRepository.findById(performance.getId()).orElseThrow().getSchedulesRebuiltOn())
                .isEqualTo(LocalDate.now());
    }

    @Test
    void 원문으로_만들_회차가_없으면_아무것도_지우지_않는다() {
        // 재생성은 내일부터 보므로 남은 기간은 내일 하루뿐이고, 원문 요일은 내일이 아니다.
        // (전에는 "토요일 전날까지"로 잡아, 금요일에 돌리면 내일인 토요일이 기간에 들어가 실패했다.)
        LocalDate end = LocalDate.now().plusDays(1);
        Performance performance = performance(koreanDayName(end.getDayOfWeek().plus(3)) + "(15:00)", end);
        PerformanceSchedule existing = schedule(performance, end.atTime(11, 0));

        regeneration.run();

        assertThat(scheduleRepository.findById(existing.getId())).isPresent();
        assertThat(performanceRepository.findById(performance.getId()).orElseThrow().getSchedulesRebuiltOn())
                .isEqualTo(LocalDate.now());
    }

    @Test
    void 재생성과_좌석_선점이_동시에_일어나도_회차_없는_예매가_생기지_않고_멈추지_않는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                setUp();
                Performance performance = performance(SHOW_TIMES, firstSaturday);
                PerformanceSchedule target = schedule(performance, LocalDate.now().plusDays(1).atTime(11, 0));
                Long seatId = seatIdOf(target);
                CountDownLatch start = new CountDownLatch(1);

                Future<?> rebuild = pool.submit(() -> { start.await(); return regeneration.run(); });
                Future<Boolean> hold = pool.submit(() -> {
                    start.await();
                    try {
                        reservationFacade.hold(HoldStrategy.PESSIMISTIC, seatId, member.getMemberId());
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                });
                start.countDown();
                rebuild.get(20, TimeUnit.SECONDS);
                boolean held = hold.get(20, TimeUnit.SECONDS);

                boolean scheduleExists = scheduleRepository.findById(target.getId()).isPresent();
                if (held) {
                    assertThat(scheduleExists).as("선점이 성공했으면 그 회차는 남아 있어야 한다 (round %d)", round).isTrue();
                    assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                            .isNotEqualTo(SeatStatus.AVAILABLE);
                } else {
                    assertThat(reservationRepository.findAll())
                            .as("선점이 실패했으면 예매가 남으면 안 된다 (round %d)", round).isEmpty();
                }
                reservationRepository.findAll().forEach(r ->
                        assertThat(scheduleRepository.findById(r.getSchedule().getId())).isPresent());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Performance performance(String showTimes, LocalDate endDate) {
        Performance performance = Performance.builder()
                .externalId("KOPIS-REGEN-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("재생성 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().minusDays(3))
                .endDate(endDate)
                // 좌석이 적으면 전부 앞줄(VIP)이 되어 등급별 가격을 확인할 수 없다.
                .totalSeatCount(100)
                .basePrice(50_000)
                .build();
        performance.updateGuidance(showTimes, PRICES);
        return performanceRepository.save(performance);
    }

    private static String koreanDayName(DayOfWeek day) {
        return "월화수목금토일".charAt(day.getValue() - 1) + "요일";
    }

    private PerformanceSchedule schedule(Performance performance, LocalDateTime showAt) {
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt)
                .totalSeats(1)
                .remainingSeats(1)
                .build());
        seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층").rowNo(1).seatNo(1)
                .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000)
                .build());
        return schedule;
    }

    private Long seatIdOf(PerformanceSchedule schedule) {
        return seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId()).get(0).getId();
    }

    private List<LocalDateTime> showTimesOf(Performance performance) {
        return scheduleRepository.findAll().stream()
                .filter(s -> s.getPerformance().getId().equals(performance.getId()))
                .map(PerformanceSchedule::getShowAt)
                .sorted()
                .toList();
    }
}
