package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.SeatRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공연 상세의 <b>좁은 화면 하단 고정 예매 바</b>(.mobilebar).
 *
 * <p>넓은 화면에서는 포스터 옆 sticky 요약 카드가 최저가와 「회차 선택하기」를 스크롤 내내
 * 들고 있는데, 그 카드는 좁은 화면에서 CSS 가 숨긴다(칸이 sticky 가 아니라 제목 위로 올라가
 * 공연명보다 최저가가 먼저 읽히기 때문). 그래서 <b>모바일에는 예매 진입점이 화면 어디에도
 * 남지 않았다</b> — 실측(390px)으로 회차 칩이 문서의 59% 지점이라 한참 스크롤해야만
 * 예매를 시작할 수 있었다. 그 자리를 메우는 것이 이 바다.
 *
 * <p>여기서 고정하는 건 <b>렌더 조건</b>이다. 보이는 자리(고정 위치·중단점)는 CSS 라
 * 테스트가 잡을 수 없지만, "예매할 회차가 없는데도 예매 CTA 가 떠 있는" 상태는 서버가
 * 만들어내는 것이라 여기서 막을 수 있다. 누를 곳이 없는 CTA 는 자리만 먹는 게 아니라,
 * 눌러서 아무 일도 안 일어나는 것을 확인시키는 쪽에 가깝다.
 *
 * <p>바와 요약 카드의 조건이 갈리면 <b>폭에 따라 예매 진입점이 있다 없다 하는</b> 상태가
 * 되므로, 둘이 항상 함께 뜨고 함께 사라지는 것도 같이 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MobileBookingBarTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;

    /**
     * 이 테스트 전용 공연을 새로 만든다. 공용 데이터를 지우고 시작하지 않는 이유는,
     * 상세 화면이 이 공연 하나만 그리므로 다른 테스트가 남긴 데이터와 섞일 일이 없어서다
     * (삭제 순서를 FK 에 맞춰 관리하는 비용만 늘어난다).
     */
    private Performance newPerformance() {
        return performanceRepository.save(Performance.builder()
                .externalId("MOBILEBAR-" + System.nanoTime())
                .title("하단 바 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().minusDays(2))
                .endDate(LocalDate.now().plusDays(5))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());
    }

    /** 좌석 1개를 가진 회차. 최저가는 좌석 등급 가격에서 나오므로 좌석까지 만들어야 한다. */
    private void scheduleWithSeat(Performance performance, LocalDateTime showAt, int price) {
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt)
                .totalSeats(10)
                .remainingSeats(10)
                .build());
        seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층").rowNo(1).seatNo(1)
                .grade(SeatGrade.VIP).status(SeatStatus.AVAILABLE).price(price)
                .build());
    }

    private String detailHtml(Performance performance) throws Exception {
        return mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @DisplayName("예매할 회차가 있으면 하단 바가 최저가와 회차 앵커를 들고 나온다")
    @Test
    void barCarriesPriceAndAnchorWhenBookable() throws Exception {
        Performance performance = newPerformance();
        scheduleWithSeat(performance, LocalDateTime.now().plusDays(1), 96_000);

        String html = detailHtml(performance);

        assertThat(html).as("하단 바가 나와야 한다").contains("class=\"mobilebar\"");
        // 최저가는 좌석 등급 가격에서 오고, 천 단위 구분과 물결표까지 화면 표기 그대로다.
        assertThat(html).as("최저가가 바에 찍혀야 한다").contains("96,000원~");
        // 특정 회차가 아니라 회차 목록으로 보낸다 — 관람일은 사용자가 고르는 것이다.
        assertThat(html).as("회차 선택 앵커로 보내야 한다").contains("href=\"#schedule-pick\"");
    }

    @DisplayName("회차가 전부 지나 예매할 게 없으면 하단 바를 아예 안 그린다")
    @Test
    void noBarWhenNothingToBook() throws Exception {
        Performance performance = newPerformance();
        // 기간은 아직 안 끝났지만(공연 종료 안내가 아니다) 남은 회차가 없는 상태 —
        // 실데이터에서 흔한 경우고, 화면으로는 "카드가 조금 달라 보일 뿐"이라 놓치기 쉽다.
        scheduleWithSeat(performance, LocalDateTime.now().minusDays(1), 96_000);
        scheduleWithSeat(performance, LocalDateTime.now().minusHours(2), 96_000);

        String html = detailHtml(performance);

        assertThat(html).as("누를 곳이 없는 예매 바가 떠 있으면 안 된다")
                .doesNotContain("class=\"mobilebar\"");
    }

    @DisplayName("하단 바와 요약 카드 CTA 는 항상 함께 뜨고 함께 사라진다")
    @Test
    void barAndSummaryCtaAppearTogether() throws Exception {
        Performance bookable = newPerformance();
        scheduleWithSeat(bookable, LocalDateTime.now().plusDays(1), 96_000);
        String bookableHtml = detailHtml(bookable);

        Performance soldPast = newPerformance();
        scheduleWithSeat(soldPast, LocalDateTime.now().minusDays(1), 96_000);
        String soldPastHtml = detailHtml(soldPast);

        // 조건이 갈리면 창 폭에 따라 예매 진입점이 있다 없다 한다 — 넓은 화면은 카드,
        // 좁은 화면은 바 하나씩만 보이므로 한쪽만 빠져도 그 폭에서는 진입점이 0 이 된다.
        assertThat(bookableHtml.contains("class=\"mobilebar\""))
                .as("예매 가능한 공연: 바와 카드 CTA 가 둘 다 있어야 한다")
                .isEqualTo(bookableHtml.contains("class=\"sc-cta\""))
                .isTrue();

        assertThat(soldPastHtml.contains("class=\"mobilebar\""))
                .as("예매할 게 없는 공연: 바와 카드 CTA 가 둘 다 없어야 한다")
                .isEqualTo(soldPastHtml.contains("class=\"sc-cta\""))
                .isFalse();
    }
}
