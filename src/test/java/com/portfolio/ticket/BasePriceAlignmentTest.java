package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이미 쌓인 공연의 기준가를 가격 원문으로 다시 계산한다.
 *
 * <p>기준가가 원문의 첫 금액(대개 최고 등급)이라 「낮은 가격순」에서 2만 원부터인 공연이 3만 원 공연
 * 사이에 섞였다. 계산 규칙만 바꾸면 이미 저장된 공연은 다시 수집될 때까지 그대로라 한 번 맞춰야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BasePriceAlignmentTest {

    @Autowired PerformanceSyncService syncService;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;

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

    private Long save(Integer basePrice, String priceGuidance) {
        Performance p = performanceRepository.save(Performance.builder()
                .externalId("KOPIS-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("기준가 " + priceGuidance)
                .category(PerformanceCategory.PLAY)
                .venue("공연장")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(100)
                .basePrice(basePrice)
                .build());
        p.updateGuidance(null, priceGuidance);
        return performanceRepository.save(p).getId();
    }

    private Integer basePriceOf(Long id) {
        return performanceRepository.findById(id).orElseThrow().getBasePrice();
    }

    @DisplayName("기준가를 원문의 좌석 최저가로 맞추고, 등급을 못 읽는 원문과 무료 공연은 그대로 둔다")
    @Test
    void alignsBasePriceToLowestSeatPrice() {
        Long twoGrades = save(30_000, "R석 30,000원, S석 20,000원");
        Long withDiscount = save(35_000, "전석 35,000원, 사전예약 10,000원");
        Long noAmount = save(40_000, "전석");
        Long free = save(0, "전석무료");
        Long already = save(20_000, "R석 30,000원, S석 20,000원");

        assertThat(syncService.alignBasePrices()).isEqualTo(1);

        assertThat(basePriceOf(twoGrades)).isEqualTo(20_000);
        assertThat(basePriceOf(withDiscount)).isEqualTo(35_000);
        assertThat(basePriceOf(noAmount)).isEqualTo(40_000);
        assertThat(basePriceOf(free)).isZero();
        assertThat(basePriceOf(already)).isEqualTo(20_000);
        assertThat(performanceRepository.findById(free).orElseThrow().isFreeAdmission()).isTrue();

        // 멱등
        assertThat(syncService.alignBasePrices()).isZero();
    }
}
