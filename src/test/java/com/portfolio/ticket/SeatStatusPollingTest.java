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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 좌석도 실시간 갱신.
 *
 * <p>좌석도는 열린 순간의 사진으로 굳어 있었다 — 보는 동안 남이 채간 좌석도 계속 예매
 * 가능한 색이었고, 사용자는 「예매하기」를 누른 뒤에야 실패를 알았다. 한 좌석을 놓고
 * 벌어지는 경쟁을 제어하는 게 이 프로젝트의 요지인데, 정작 경쟁이 벌어지는 화면이 그
 * 사실을 안 보여주고 있었다.
 *
 * <p><b>이 응답의 규칙은 "고를 수 없는 좌석만 담는다" 하나다.</b> 화면은 목록에 없으면
 * 예매 가능으로 읽으므로, 선점이 만료돼 목록에서 빠지는 것이 곧 "다시 살 수 있게 됨"이
 * 된다. 그래서 아래 테스트는 <b>빠지는 쪽</b>을 특히 확인한다 — 담기는 쪽만 맞으면
 * 좌석은 한 번 회색이 된 뒤 영원히 돌아오지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeatStatusPollingTest {

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
    private Seat held;
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
                .externalId("POLL-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("좌석 갱신 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(3)
                .basePrice(50_000)
                .build());

        schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(1).withNano(0))
                .totalSeats(3).remainingSeats(3)
                .build());

        available = seat(1);

        // save() 결과를 다시 받아야 한다. 좌석에는 @Version 이 있어서, 저장 전 인스턴스를
        // 계속 들고 있다가 나중에 또 save 하면 버전이 안 맞아 낙관적 락 예외가 난다.
        Seat toHold = seat(2);
        toHold.hold();
        held = seatRepository.save(toHold);

        Seat toSell = seat(3);
        toSell.hold();
        toSell.sell();
        sold = seatRepository.save(toSell);
    }

    /** 상태를 바꿀 때는 DB 에서 다시 읽어 온다 (위와 같은 이유). */
    private void release(Seat seat) {
        Seat fresh = seatRepository.findById(seat.getId()).orElseThrow();
        fresh.release();
        seatRepository.save(fresh);
    }

    private Seat seat(int seatNo) {
        return seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("A").rowNo(1).seatNo(seatNo)
                .grade(SeatGrade.VIP).price(50_000)
                .build());
    }

    @DisplayName("고를 수 없는 좌석만 돌려준다 — 예매 가능 좌석은 목록에 없다")
    @Test
    void returnsOnlyUnavailableSeats() throws Exception {
        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + held.getId() + ")].status").value("HELD"))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + sold.getId() + ")].status").value("SOLD"))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + available.getId() + ")]").isEmpty());
    }

    /**
     * 이게 이 기능의 절반이다. 선점은 10분이면 풀리는데, 풀린 좌석이 응답에 계속 남아 있으면
     * 화면은 한 번 회색이 된 좌석을 영원히 되살리지 못한다 — 아무도 안 산 좌석이 화면에서만
     * 영구히 매진된 것처럼 보인다.
     */
    @DisplayName("선점이 풀린 좌석은 목록에서 빠진다 (= 화면에서 다시 살아난다)")
    @Test
    void releasedSeatDisappearsFromList() throws Exception {
        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + held.getId() + ")]").isNotEmpty());

        release(held);

        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + held.getId() + ")]").isEmpty());
    }

    /**
     * 좌석도(/schedules/**)는 비로그인으로 볼 수 있는 화면이다. 갱신만 인증을 요구하면
     * 로그인하지 않은 사용자의 좌석도만 사진으로 굳는다 — 정작 그 사람이 로그인하러 갔다
     * 오는 사이 좌석이 나가는데도.
     */
    @DisplayName("비로그인도 조회할 수 있다 — 좌석도 자체가 공개 화면이다")
    @Test
    void publiclyReadable() throws Exception {
        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(status().isOk());
    }

    @DisplayName("좌석이 전부 예매 가능하면 빈 목록")
    @Test
    void emptyWhenNothingTaken() throws Exception {
        release(held);
        seatRepository.delete(seatRepository.findById(sold.getId()).orElseThrow());

        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").isArray())
                .andExpect(jsonPath("$.seats.length()").value(0));
    }

    /** 없는 회차를 물어도 화면이 죽으면 안 된다 — 갱신은 5초마다 도는 요청이라 한 번의 예외가
        콘솔을 채운다. 좌석이 없는 것과 회차가 없는 것을 굳이 구분하지 않는다. */
    @DisplayName("없는 회차는 빈 목록으로 답한다")
    @Test
    void unknownScheduleReturnsEmpty() throws Exception {
        mvc.perform(get("/api/schedules/{id}/seat-status", 999_999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(0));
    }

    @DisplayName("다른 회차의 좌석은 섞이지 않는다")
    @Test
    void doesNotLeakOtherSchedules() throws Exception {
        PerformanceSchedule other = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(schedule.getPerformance())
                .showAt(schedule.getShowAt().plusDays(1))
                .totalSeats(1).remainingSeats(1)
                .build());
        Seat otherSeat = seatRepository.save(Seat.builder()
                .schedule(other).section("A").rowNo(1).seatNo(1)
                .grade(SeatGrade.VIP).price(50_000).build());
        otherSeat.hold();
        otherSeat = seatRepository.save(otherSeat);

        mvc.perform(get("/api/schedules/{id}/seat-status", schedule.getId()))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[?(@.seatId == " + otherSeat.getId() + ")]").isEmpty());
    }

    /** 화면이 되살릴 때 쓰는 좌석 식별자와 좌석도가 그리는 식별자가 같아야 한다. 다르면
        갱신이 아무 좌석도 못 찾고 조용히 아무 일도 일어나지 않는다. */
    @DisplayName("좌석도가 그리는 data-seat-id 와 같은 식별자를 쓴다")
    @Test
    void usesSameSeatIdAsRenderedMap() throws Exception {
        String html = mvc.perform(get("/schedules/{id}/seats", schedule.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-seat-id=\"" + held.getId() + "\"");
        // 갱신 주소는 화면에서 조립된다(`/api/schedules/${SCHEDULE_ID}/seat-status`) — 그래서
        // HTML 에는 완성된 주소가 없다. 조립 재료인 회차 번호와 경로 조각을 각각 확인한다.
        assertThat(html).contains("const SCHEDULE_ID = " + schedule.getId());
        assertThat(html).contains("/seat-status");
    }
}
