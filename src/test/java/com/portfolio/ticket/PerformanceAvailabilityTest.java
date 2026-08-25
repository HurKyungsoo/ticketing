package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.service.PerformanceListService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이슈 #2. 목록/홈 카드에 예매 가능 여부(매진 · 매진임박 · 오픈 예정 · 예매처 정보 없음)를
 * 배지로 보여준다 — "헛클릭"(예매 못 하는 공연인데 상세까지 들어갔다가 알게 되는 것)을
 * 줄이는 게 목적이다.
 *
 * <p>배지는 항상 "다음 회차"(카드에 적힌 그 날짜)에 대한 것이다. 그 회차가 매진이어도
 * 더 뒤 회차에 자리가 있을 수 있는데, 그건 상세의 회차 선택 화면에서 확인한다 — 카드
 * 배지가 모든 회차를 훑으면 카드에 적힌 날짜와 배지가 서로 다른 회차를 말하게 된다
 * (PerformanceListRow.availability 주석 참고).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerformanceAvailabilityTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceListService listService;
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

    private PerformanceListRow firstRow() {
        return listService.search(null, null, null, null, "ongoing", null, null, null, null, 0)
                .performances().get(0);
    }

    private Performance performanceWith(LocalDate startDate, LocalDate endDate) {
        return performanceRepository.save(Performance.builder()
                .externalId("AVAIL-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("가용성테스트")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(startDate)
                .endDate(endDate)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    private void scheduleWith(Performance performance, LocalDateTime showAt, int remainingSeats) {
        scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt.withNano(0))
                .totalSeats(100)
                .remainingSeats(remainingSeats)
                .build());
    }

    @DisplayName("다음 회차 잔여석이 0이면 매진이다")
    @Test
    void soldOutWhenNextShowHasNoSeatsLeft() {
        Performance p = performanceWith(LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        scheduleWith(p, LocalDateTime.now().plusDays(3).withHour(19).withMinute(0), 0);

        PerformanceListRow.Availability avail = firstRow().availability(LocalDate.now());

        assertThat(avail).isNotNull();
        assertThat(avail.label()).isEqualTo("매진");
        assertThat(avail.cssClass()).isEqualTo("avail-soldout");
    }

    @DisplayName("다음 회차 잔여석이 10석 이하(0 제외)면 매진임박이다")
    @Test
    void almostSoldOutWhenSeatsAreLow() {
        Performance p = performanceWith(LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        scheduleWith(p, LocalDateTime.now().plusDays(3).withHour(19).withMinute(0), 10);

        PerformanceListRow.Availability avail = firstRow().availability(LocalDate.now());

        assertThat(avail).isNotNull();
        assertThat(avail.label()).isEqualTo("매진임박");
        assertThat(avail.cssClass()).isEqualTo("avail-low");
    }

    @DisplayName("잔여석이 넉넉하면 배지가 없다(예매 가능)")
    @Test
    void noBadgeWhenSeatsArePlentiful() {
        Performance p = performanceWith(LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        scheduleWith(p, LocalDateTime.now().plusDays(3).withHour(19).withMinute(0), 11);

        PerformanceListRow.Availability avail = firstRow().availability(LocalDate.now());

        assertThat(avail).isNull();
    }

    @DisplayName("회차가 아직 없고 시작 전이면 오픈 예정이다")
    @Test
    void notYetOpenWhenNoScheduleAndNotStarted() {
        performanceWith(LocalDate.now().plusDays(5), LocalDate.now().plusDays(35));

        PerformanceListRow.Availability avail = firstRow().availability(LocalDate.now());

        assertThat(avail).isNotNull();
        assertThat(avail.label()).isEqualTo("오픈 예정");
        assertThat(avail.cssClass()).isEqualTo("avail-pending");
    }

    @DisplayName("회차가 없고 이미 시작했으면 예매처 정보 없음이다")
    @Test
    void infoUnavailableWhenNoScheduleButAlreadyStarted() {
        performanceWith(LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));

        PerformanceListRow.Availability avail = firstRow().availability(LocalDate.now());

        assertThat(avail).isNotNull();
        assertThat(avail.label()).isEqualTo("예매처 정보 없음");
        assertThat(avail.cssClass()).isEqualTo("avail-unknown");
    }

    /**
     * 화면 렌더까지 확인한다 - 서비스 계층 계산이 맞아도 템플릿의 클래스명 오타나
     * th:if 조건 실수로 안 그려지는 경우를 이 테스트가 잡는다(이슈 #1 되돌릴 때
     * 실제로 겪은 종류의 함정 - th:classappend 가 만드는 class 속성은 값이 여러 개
     * 합쳐질 수 있어 문자열 매칭 하나로는 안 걸린다는 걸 배웠다).
     */
    @DisplayName("목록 화면에 매진 배지가 실제로 그려진다")
    @Test
    void soldOutBadgeRendersOnListPage() throws Exception {
        Performance p = performanceWith(LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        scheduleWith(p, LocalDateTime.now().plusDays(3).withHour(19).withMinute(0), 0);

        mockMvc.perform(get("/performances").param("status", "ongoing"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("class=\"card-avail avail-soldout\"");
                    assertThat(html).contains("매진");
                });
    }
}
