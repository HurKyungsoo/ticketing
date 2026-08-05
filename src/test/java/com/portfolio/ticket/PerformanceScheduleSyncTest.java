package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 생성/보충 규칙.
 *
 * <p>예전 규칙은 시작일부터 최대 8일치만 회차를 만들었다. 공연 기간이 길면 그 8일이
 * 전부 지나가 버려도 이후 동기화에서 회차를 더 만들지 않아, 실제 서비스 중인 공연의
 * 대부분이 "예매 가능한 회차 없음" 으로 죽어 있던 버그가 있었다. 지금은 신규 수집 시
 * 오늘(또는 시작일, 더 늦은 쪽)부터 종료일까지 다 만들고, 이미 있는 공연은 동기화마다
 * 마지막 회차 다음 날부터 종료일까지 빠진 만큼만 보충한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerformanceScheduleSyncTest {

    @Autowired PerformanceSyncService syncService;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired WishlistRepository wishlistRepository;

    @BeforeEach
    void setUp() {
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    private ExternalPerformance external(String externalId, LocalDate start, LocalDate end) {
        return ExternalPerformance.builder()
                .externalId(externalId)
                .sourceType(SourceType.KOPIS)
                .title("테스트 공연")
                .genre("연극")
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(start)
                .endDate(end)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build();
    }

    @DisplayName("신규 공연은 8일로 잘리지 않고 기간 전체에 회차가 생긴다")
    @Test
    void createsScheduleForEntireRun() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(20);

        syncService.sync(List.of(external("KOPIS-LONGRUN", start, end)));

        Performance saved = performanceRepository.findByExternalId("KOPIS-LONGRUN").orElseThrow();
        assertThat(scheduleRepository.findByPerformanceIdOrderByShowAtAsc(saved.getId())).hasSize(21);
    }

    @DisplayName("이미 시작한 공연을 처음 수집해도 과거 회차는 만들지 않고 오늘부터 시작한다")
    @Test
    void newlyCollectedButAlreadyRunning_startsFromToday() {
        LocalDate start = LocalDate.now().minusDays(15);
        LocalDate end = LocalDate.now().plusDays(5);

        syncService.sync(List.of(external("KOPIS-MIDRUN", start, end)));

        Performance saved = performanceRepository.findByExternalId("KOPIS-MIDRUN").orElseThrow();
        List<PerformanceSchedule> schedules = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(saved.getId());
        assertThat(schedules).hasSize(6);
        assertThat(schedules.get(0).getShowAt().toLocalDate()).isEqualTo(LocalDate.now());
    }

    @DisplayName("기존 공연은 동기화마다 마지막 회차 다음 날부터 종료일까지 보충한다")
    @Test
    void topsUpExistingPerformanceOnEverySync() {
        LocalDate oldStart = LocalDate.now().minusDays(40);
        LocalDate end = LocalDate.now().plusDays(10);

        // 예전 버그를 흉내낸다: 시작일부터 8일치만 있고 전부 과거.
        Performance broken = performanceRepository.save(Performance.builder()
                .externalId("KOPIS-BROKEN")
                .sourceType(SourceType.KOPIS)
                .title("테스트 공연")
                .category(PerformanceCategory.ETC)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(oldStart)
                .endDate(end)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        for (int i = 0; i < 8; i++) {
            scheduleRepository.save(PerformanceSchedule.builder()
                    .performance(broken)
                    .showAt(LocalDateTime.of(oldStart.plusDays(i), LocalTime.of(19, 0)))
                    .totalSeats(100)
                    .remainingSeats(100)
                    .build());
        }
        assertThat(scheduleRepository.findByPerformanceIdOrderByShowAtAsc(broken.getId())).hasSize(8);

        syncService.sync(List.of(external("KOPIS-BROKEN", oldStart, end)));

        List<PerformanceSchedule> schedules =
                scheduleRepository.findByPerformanceIdOrderByShowAtAsc(broken.getId());
        // 기존 8개는 그대로 남고, 오늘부터 end 까지가 새로 채워진다.
        assertThat(schedules).hasSizeGreaterThan(8);
        assertThat(schedules.get(schedules.size() - 1).getShowAt().toLocalDate()).isEqualTo(end);
        boolean hasFutureBookable = schedules.stream()
                .anyMatch(s -> !s.getShowAt().isBefore(LocalDateTime.now()));
        assertThat(hasFutureBookable).isTrue();
    }

    @DisplayName("같은 데이터로 다시 동기화해도 회차가 중복 생성되지 않는다")
    @Test
    void isIdempotentAcrossSyncPasses() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(10);
        ExternalPerformance ext = external("KOPIS-IDEMPOTENT", start, end);

        syncService.sync(List.of(ext));
        syncService.sync(List.of(ext));

        Performance saved = performanceRepository.findByExternalId("KOPIS-IDEMPOTENT").orElseThrow();
        assertThat(scheduleRepository.findByPerformanceIdOrderByShowAtAsc(saved.getId())).hasSize(11);
    }

    /**
     * KOPIS 증분 수집은 원본이 바뀐 건만 다시 내려온다. 한 번 등록된 뒤로 원본이
     * 안 바뀐 공연은 sync() 자체가 호출되지 않으므로 topUpSchedules 도 못 탄다 —
     * 실기동 중 실제로 발견한 사각지대다(id=95 KOPIS-PF297332, 최근 동기화가
     * 안 잡아서 회차가 6/16 이후로 안 늘어나 있었다). topUpStaleSchedules 는 그
     * 사각지대를 외부 응답과 무관하게 로컬 DB만 보고 잡아야 한다.
     */
    @DisplayName("증분 수집에 다시 안 잡힌 공연도 topUpStaleSchedules 가 잡아낸다")
    @Test
    void topUpStaleSchedulesFixesPerformanceNeverRevisitedBySync() {
        LocalDate oldStart = LocalDate.now().minusDays(40);
        LocalDate end = LocalDate.now().plusDays(10);

        Performance stale = performanceRepository.save(Performance.builder()
                .externalId("KOPIS-STALE")
                .sourceType(SourceType.KOPIS)
                .title("테스트 공연")
                .category(PerformanceCategory.ETC)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(oldStart)
                .endDate(end)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        for (int i = 0; i < 8; i++) {
            scheduleRepository.save(PerformanceSchedule.builder()
                    .performance(stale)
                    .showAt(LocalDateTime.of(oldStart.plusDays(i), LocalTime.of(19, 0)))
                    .totalSeats(100)
                    .remainingSeats(100)
                    .build());
        }

        // sync() 를 한 번도 안 거치고 이 메서드만 호출한다 — 증분 수집이 아예 다시 안
        // 내려주는 상황을 그대로 흉내낸다.
        int topped = syncService.topUpStaleSchedules();

        assertThat(topped).isEqualTo(1);
        List<PerformanceSchedule> schedules =
                scheduleRepository.findByPerformanceIdOrderByShowAtAsc(stale.getId());
        assertThat(schedules).hasSizeGreaterThan(8);
        assertThat(schedules.get(schedules.size() - 1).getShowAt().toLocalDate()).isEqualTo(end);
        assertThat(schedules.stream().anyMatch(s -> !s.getShowAt().isBefore(LocalDateTime.now()))).isTrue();
    }

    @DisplayName("topUpStaleSchedules 는 이미 미래 회차가 있는 공연은 건드리지 않는다")
    @Test
    void topUpStaleSchedulesSkipsPerformancesWithFutureSchedules() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(5);
        syncService.sync(List.of(external("KOPIS-HEALTHY", start, end)));
        Performance healthy = performanceRepository.findByExternalId("KOPIS-HEALTHY").orElseThrow();
        int before = scheduleRepository.findByPerformanceIdOrderByShowAtAsc(healthy.getId()).size();

        int topped = syncService.topUpStaleSchedules();

        assertThat(topped).isZero();
        assertThat(scheduleRepository.findByPerformanceIdOrderByShowAtAsc(healthy.getId())).hasSize(before);
    }

    @DisplayName("topUpStaleSchedules 는 이미 끝난 공연은 건드리지 않는다")
    @Test
    void topUpStaleSchedulesSkipsEndedPerformances() {
        LocalDate start = LocalDate.now().minusDays(20);
        LocalDate end = LocalDate.now().minusDays(1);

        performanceRepository.save(Performance.builder()
                .externalId("KOPIS-ENDED")
                .sourceType(SourceType.KOPIS)
                .title("테스트 공연")
                .category(PerformanceCategory.ETC)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(start)
                .endDate(end)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());

        int topped = syncService.topUpStaleSchedules();

        assertThat(topped).isZero();
    }
}
