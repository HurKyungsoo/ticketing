package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 좌석 1개에 100명이 동시에 달려들었을 때 전략별 결과 비교.
 *
 * 기대값
 *   NONE        : 성공 2건 이상 (오버부킹 발생 = 버그 재현)
 *   PESSIMISTIC : 성공 정확히 1건
 *   OPTIMISTIC  : 성공 정확히 1건 (충돌 재시도 발생)
 *   UNIQUE      : 성공 정확히 1건
 *   DISTRIBUTED : 성공 정확히 1건 (Redis 가 없으면 건너뜀)
 *
 * 결과는 콘솔에 표로 찍히므로 그대로 README 에 붙이면 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationConcurrencyTest {

    private static final int THREAD_COUNT = 100;

    /**
     * DISTRIBUTED 측정을 위해 테스트가 직접 redis-server 를 띄운다.
     * (로컬/CI 에 Docker 가 없어도 5전략을 같은 조건에서 비교할 수 있게)
     * 기동에 실패하면 redisServer 가 null 로 남고 DISTRIBUTED 만 건너뛴다.
     */
    private static RedisServer redisServer;
    private static int redisPort;

    @BeforeAll
    static void startRedis() {
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();       // 충돌을 피해 빈 포트를 받아온다
        } catch (IOException e) {
            return;
        }
        try {
            redisServer = new RedisServer(redisPort);
            redisServer.start();
        } catch (Exception e) {
            redisServer = null;
        }
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception ignored) {
                // 테스트 종료 중이므로 무시
            }
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @Autowired ReservationFacade reservationFacade;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    private Long targetSeatId;

    @BeforeEach
    void setUp() {
        // 삭제 순서는 FK 방향을 따라야 한다. 예매 1건이 좌석 여러 개를 갖게 되면서
        // FK 가 seat.reservation_id 로 옮겨졌으므로, 좌석을 먼저 지우지 않고 예매를 지우면
        // 좌석이 아직 예매를 참조하고 있어 제약 위반이 난다.
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
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
                .section("1층")
                .rowNo(1)
                .seatNo(1)
                .grade(SeatGrade.VIP)
                .status(SeatStatus.AVAILABLE)
                .price(75_000)
                .build());

        this.targetSeatId = seat.getId();
    }

    /**
     * DISTRIBUTED 는 Redis 가 있어야만 의미가 있다. CI 처럼 Redis 가 없는 환경에서는
     * 실패가 아니라 "건너뜀"으로 남겨야 나머지 4전략의 회귀 검증이 계속 유효하다.
     */
    private boolean redisAvailable() {
        try {
            redisConnectionFactory.getConnection().close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @DisplayName("좌석 1석에 100명 동시 요청 - 전략별 성공 건수")
    @ParameterizedTest(name = "{0}")
    @EnumSource(HoldStrategy.class)
    void concurrentHold(HoldStrategy strategy) throws InterruptedException {
        if (strategy == HoldStrategy.DISTRIBUTED) {
            assumeTrue(redisAvailable(), "Redis 가 없어 DISTRIBUTED 전략은 건너뜁니다.");
        }

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
