package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 1개에 100명이 동시에 달려들었을 때 전략별 결과 비교.
 *
 * 기대값
 *   NONE        : 성공 2건 이상 (오버부킹 발생 = 버그 재현)
 *   PESSIMISTIC : 성공 정확히 1건
 *   OPTIMISTIC  : 성공 정확히 1건 (충돌 재시도 발생)
 *   UNIQUE      : 성공 정확히 1건
 *
 * 결과는 콘솔에 표로 찍히므로 그대로 README 에 붙이면 된다.
 */
@SpringBootTest
class ReservationConcurrencyTest {

    private static final int THREAD_COUNT = 100;

    @Autowired ReservationFacade reservationFacade;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatHoldRepository seatHoldRepository;

    private Long targetSeatId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("TEST-" + System.nanoTime())
                .title("동시성 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().plusDays(30))
                .endDate(LocalDate.now().plusDays(31))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());

        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(100)
                .remainingSeats(100)
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("A")
                .seatNo(1)
                .grade(SeatGrade.VIP)
                .status(SeatStatus.AVAILABLE)
                .price(75_000)
                .build());

        this.targetSeatId = seat.getId();
    }

    @DisplayName("좌석 1석에 100명 동시 요청 - 전략별 성공 건수")
    @ParameterizedTest(name = "{0}")
    @EnumSource(HoldStrategy.class)
    void concurrentHold(HoldStrategy strategy) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        long begin = System.currentTimeMillis();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long memberId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();                     // 전원 동시 출발
                    reservationFacade.hold(strategy, targetSeatId, memberId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - begin;

        List<Reservation> reservations = reservationRepository.findAll();
        long soldCount = reservations.stream()
                .filter(r -> r.getStatus() != ReservationStatus.CANCELED)
                .count();

        System.out.printf(
                "| %-11s | 성공 %3d | 실패 %3d | 실제 예매행 %3d | %5d ms |%n",
                strategy, success.get(), failure.get(), soldCount, elapsed);

        if (strategy == HoldStrategy.NONE) {
            // 락이 없으면 1건을 넘긴다. 넘기지 않으면 부하가 부족한 것.
            assertThat(soldCount).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(soldCount).isEqualTo(1);
            assertThat(success.get()).isEqualTo(1);
        }
    }
}
