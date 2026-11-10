package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.external.KopisGuidanceParser;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.service.SeatGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 좌석은 원본(KOPIS pcseguidance)에 있는 등급과 가격으로만 만든다. */
@SpringBootTest
@ActiveProfiles("test")
class SeatGradeFromGuidanceTest {

    @Autowired SeatGenerator seatGenerator;
    @Autowired SeatRepository seatRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    @Test
    void 원본에_R석과_S석만_있으면_VIP석과_A석을_만들지_않는다() {
        List<Seat> seats = generate(KopisGuidanceParser.pricesByGrade("R석 66,000원, S석 44,000원"));

        Map<SeatGrade, Integer> priceByGrade = seats.stream()
                .collect(Collectors.toMap(Seat::getGrade, Seat::getPrice, (a, b) -> a));
        assertThat(priceByGrade).containsOnlyKeys(SeatGrade.R, SeatGrade.S);
        assertThat(priceByGrade).containsEntry(SeatGrade.R, 66_000).containsEntry(SeatGrade.S, 44_000);
        // 앞줄이 좋은 등급이라는 배치는 그대로다.
        assertThat(seats.get(0).getGrade()).isEqualTo(SeatGrade.R);
        assertThat(seats.get(seats.size() - 1).getGrade()).isEqualTo(SeatGrade.S);
    }

    @Test
    void 전석_한_가격이면_모든_좌석이_그_가격이다() {
        List<Seat> seats = generate(KopisGuidanceParser.pricesByGrade("전석 40,000원, 사전예약 15,000원"));

        assertThat(seats).extracting(Seat::getPrice).containsOnly(40_000);
        assertThat(seats).extracting(Seat::getGrade).containsOnly(SeatGrade.S);
    }

    @Test
    void 네_등급이_다_있으면_종전과_같다() {
        List<Seat> seats = generate(KopisGuidanceParser.pricesByGrade(
                "VIP석 165,000원, R석 143,000원, S석 121,000원, A석 99,000원"));

        assertThat(seats).extracting(Seat::getGrade)
                .contains(SeatGrade.VIP, SeatGrade.R, SeatGrade.S, SeatGrade.A);
    }

    @Test
    void 가격_원문이_없으면_종전대로_네_등급을_기준가_비율로_만든다() {
        List<Seat> seats = generate(null);

        assertThat(seats).extracting(Seat::getGrade)
                .contains(SeatGrade.VIP, SeatGrade.R, SeatGrade.S, SeatGrade.A);
        assertThat(seats).filteredOn(s -> s.getGrade() == SeatGrade.VIP)
                .extracting(Seat::getPrice).containsOnly(75_000);
    }

    private List<Seat> generate(Map<SeatGrade, Integer> pricesByGrade) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("GRADE-" + System.nanoTime())
                .title("등급 테스트")
                .venue("등급 테스트홀 " + System.nanoTime())
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(3))
                .totalSeats(100)
                .remainingSeats(100)
                .build());
        seatGenerator.generate(schedule.getId(), null, performance.getVenue(), 100, 50_000, pricesByGrade);
        return seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId()).stream()
                .sorted(java.util.Comparator.comparing(Seat::getRowNo).thenComparing(Seat::getSection))
                .toList();
    }
}
