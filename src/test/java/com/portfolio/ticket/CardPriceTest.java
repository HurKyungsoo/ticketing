package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 목록 카드에 가격(기준가 = 좌석 최저가)을 적는다. 「낮은 가격순」으로 정렬해 놓고 카드에
 * 가격이 없어서, 정렬이 맞는지 사용자가 확인할 방법이 없었다(2026-11-18 전체 점검 발견 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CardPriceTest {

    @Autowired MockMvc mockMvc;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
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

    private void performance(String title, Integer basePrice) {
        performanceRepository.save(Performance.builder()
                .externalId("PRICE-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(100)
                .basePrice(basePrice)
                .build());
    }

    @DisplayName("가격 표기는 상세 화면 최저가와 같다 — 무료 / 40,000원~ / 모르면 없음")
    @Test
    void priceLabel() {
        assertThat(PerformanceListRow.priceFor(0)).isEqualTo("무료");
        assertThat(PerformanceListRow.priceFor(2_000)).isEqualTo("2,000원~");
        assertThat(PerformanceListRow.priceFor(144_000)).isEqualTo("144,000원~");
        assertThat(PerformanceListRow.priceFor(null)).isNull();
    }

    @DisplayName("목록 카드에 가격이 그려지고, 가격을 모르는 공연은 가격 칸 자체가 없다")
    @Test
    void listCardShowsPrice() throws Exception {
        performance("유료공연", 40_000);
        performance("무료공연", 0);
        performance("가격모름", null);

        mockMvc.perform(get("/performances").param("status", "ongoing").param("sort", "priceAsc"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("40,000원~");
                    assertThat(html).contains(">무료</span>");
                    // 세 카드 중 가격을 아는 두 장에만 가격 칸이 있다
                    assertThat(html.split("class=\"m-price\"", -1).length - 1).isEqualTo(2);
                    // 낮은 가격순: 무료 → 40,000원 → 모름
                    assertThat(html.indexOf("무료공연")).isLessThan(html.indexOf("유료공연"));
                    assertThat(html.indexOf("유료공연")).isLessThan(html.indexOf("가격모름"));
                });
    }
}
