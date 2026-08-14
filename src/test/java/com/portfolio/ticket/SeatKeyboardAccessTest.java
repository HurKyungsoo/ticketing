package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
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
 * 좌석도 키보드 접근성.
 *
 * <p>좌석이 전부 탭 정지점이면 가운데 좌석 하나를 고르려고 Tab 을 백 번 눌러야 한다
 * (2,481석 홀에서는 천 번이 넘는다). 격자 UI 의 표준대로 구역마다 정지점 하나만 두고
 * 안에서는 방향키로 옮긴다.
 *
 * <p><b>이 테스트가 지키는 것은 "JS 가 꺼져도 좌석을 고를 수 있다"이다.</b> 이동 로직
 * 자체는 브라우저에서만 돌아가므로 여기서 검증할 수 없다 — 대신 서버가 그리는 초기
 * HTML 이 스크립트 없이도 쓸 만한 상태인지를 고정한다. roving tabindex 를 서버 렌더에
 * 박아버리면(모든 좌석 tabindex=-1) JS 가 안 뜬 순간 좌석도가 통째로 키보드에서
 * 사라진다 — 그래서 초기 HTML 은 예매 가능한 좌석을 그대로 탭 가능하게 두고, 정지점
 * 정리는 스크립트가 뜬 뒤에 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeatKeyboardAccessTest {

    @Autowired MockMvc mvc;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;

    private PerformanceSchedule schedule;
    private Seat available;
    private Seat sold;

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("KBD-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("키보드 접근성 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀").region("서울특별시")
                .startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(2).basePrice(50_000)
                .build());

        schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(1).withNano(0))
                .totalSeats(2).remainingSeats(2)
                .build());

        available = seatRepository.save(Seat.builder()
                .schedule(schedule).section("A").rowNo(1).seatNo(1)
                .grade(SeatGrade.VIP).price(50_000).build());

        Seat toSell = seatRepository.save(Seat.builder()
                .schedule(schedule).section("A").rowNo(1).seatNo(2)
                .grade(SeatGrade.VIP).price(50_000).build());
        toSell.hold();
        toSell.sell();
        sold = seatRepository.save(toSell);
    }

    private String seatMapHtml() throws Exception {
        return mvc.perform(get("/schedules/{id}/seats", schedule.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * 스크립트가 안 떴을 때의 최소 보장. 예매 가능한 좌석에 tabindex 를 박지 않으면
     * 버튼 기본값(0)이라 그대로 탭으로 닿는다 — 느릴 뿐 못 쓰는 상태는 아니다.
     */
    @DisplayName("초기 HTML 은 예매 가능 좌석을 탭에서 빼지 않는다 (JS 없이도 고를 수 있어야 한다)")
    @Test
    void availableSeatIsTabbableWithoutScript() throws Exception {
        String html = seatMapHtml();
        String seatTag = tagOf(html, available.getId());

        assertThat(seatTag).doesNotContain("tabindex");
        assertThat(seatTag).doesNotContain("aria-disabled");
        assertThat(seatTag).contains("aria-pressed=\"false\"");
    }

    /**
     * 팔린 좌석은 고를 수 없다는 사실이 "이름"에 들어 있어야 한다. 색으로만 구분하면
     * 스크린리더 사용자는 어디가 팔렸는지 알 수 없고, 그러면 좌석도를 읽을 수 없다.
     */
    @DisplayName("판매 완료 좌석은 상태를 이름에 담고 aria-disabled 로 표시한다")
    @Test
    void soldSeatAnnouncesItsState() throws Exception {
        String seatTag = tagOf(seatMapHtml(), sold.getId());

        assertThat(seatTag).contains("aria-disabled=\"true\"");
        assertThat(seatTag).contains("이미 판매된 좌석");
        // 못 고르는 좌석에는 "눌린 상태"라는 개념이 없다.
        assertThat(seatTag).doesNotContain("aria-pressed");
    }

    /** 네이티브 disabled 를 쓰면 클릭 이벤트 자체가 안 만들어져, 왜 못 고르는지 탭으로
        안내할 방법이 사라진다(모바일은 호버가 없다). 이미 겪어서 되돌린 결정이라 고정한다. */
    @DisplayName("판매 완료 좌석에 네이티브 disabled 를 쓰지 않는다")
    @Test
    void soldSeatIsNotNativelyDisabled() throws Exception {
        assertThat(tagOf(seatMapHtml(), sold.getId())).doesNotContain(" disabled");
    }

    @DisplayName("키보드 조작법을 스크린리더에 안내한다")
    @Test
    void announcesKeyboardInstructions() throws Exception {
        String html = seatMapHtml();

        assertThat(html).contains("좌석은 방향키로 옮겨 다니고");
        // 눈으로 쓰는 사람에게는 필요 없는 설명이라 화면에는 안 보여야 한다.
        assertThat(html).contains("class=\"sr-only\"");
    }

    /** 이동 로직은 이 요소들을 기준으로 격자를 만든다. 구조가 바뀌면 키보드 이동이
        조용히 죽으므로(예외도 안 난다) 여기서 고정한다. */
    @DisplayName("키보드 이동이 기준으로 삼는 구조(.house / .row / .seat)를 유지한다")
    @Test
    void keepsGridStructure() throws Exception {
        String html = seatMapHtml();

        assertThat(html).contains("class=\"house\"");
        assertThat(html).contains("class=\"row\"");
        assertThat(html).contains("class=\"seat");
    }

    /** 해당 좌석의 여는 태그만 잘라낸다. */
    private String tagOf(String html, Long seatId) {
        int idx = html.indexOf("data-seat-id=\"" + seatId + "\"");
        assertThat(idx).as("좌석 %d 가 좌석도에 없다", seatId).isGreaterThan(-1);
        int start = html.lastIndexOf("<button", idx);
        int end = html.indexOf('>', idx);
        return html.substring(start, end + 1);
    }
}
