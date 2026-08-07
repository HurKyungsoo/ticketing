package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실패 경로가 사람이 읽을 수 있는 화면 / 뜻이 맞는 상태코드로 나가는지 검증한다.
 *
 * 이 테스트가 생긴 이유: 전에는 어드바이스도 에러 템플릿도 없어서
 *   - 없는 공연 주소       -> Whitelabel 500 (404 여야 함)
 *   - 남의 예매 결제 페이지 -> Whitelabel 500 (403 이어야 함)
 *   - 남의 예매 취소 API    -> 400            (403 이어야 함)
 * 이 나갔다. 상태코드가 틀리면 클라이언트가 "다시 시도해야 하는지"를 판단할 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorHandlingTest {

    @Autowired MockMvc mockMvc;
    @Autowired ReservationFacade reservationFacade;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatHoldRepository seatHoldRepository;

    /** 예매를 소유한 회원 */
    private CustomUserDetails owner;
    /** 남의 예매를 넘겨다보는 회원 */
    private CustomUserDetails stranger;
    private String reservationNo;

    @BeforeEach
    void setUp() {
        // 삭제 순서는 FK 방향을 따른다(좌석이 예매를 참조하므로 좌석부터).
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        owner = new CustomUserDetails(memberRepository.save(member("owner")));
        stranger = new CustomUserDetails(memberRepository.save(member("stranger")));

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("ERR-" + System.nanoTime())
                .title("에러 처리 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().plusDays(30))
                .endDate(LocalDate.now().plusDays(31))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());

        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(30).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(10)
                .remainingSeats(10)
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

        reservationNo = reservationFacade
                .hold(HoldStrategy.PESSIMISTIC, seat.getId(), owner.getMemberId())
                .getReservationNo();
    }

    private Member member(String loginId) {
        return Member.builder()
                .loginId(loginId)
                .password("{noop}pw")
                .nickname(loginId)
                .role(MemberRole.USER)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /* ------------------------------------------------------------------
     *  화면(Thymeleaf) — HTML 에러 페이지가 나가야 한다
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("없는 공연 상세 -> 404 페이지 (종전 Whitelabel 500)")
    void unknownPerformanceReturns404Page() throws Exception {
        mockMvc.perform(get("/performances/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("경로변수 타입 불일치(/performances/abc) -> 404 페이지")
    void typeMismatchReturns404Page() throws Exception {
        mockMvc.perform(get("/performances/abc"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("없는 회차 좌석도 -> 404 페이지")
    void unknownScheduleReturns404Page() throws Exception {
        mockMvc.perform(get("/schedules/99999999/seats"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("남의 예매 결제 페이지 -> 403 페이지 (종전 Whitelabel 500)")
    void otherMembersPaymentPageReturns403Page() throws Exception {
        mockMvc.perform(get("/reservations/{no}/payment", reservationNo).with(user(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/403"));
    }

    @Test
    @DisplayName("없는 예매번호 결제 페이지 -> 404 페이지")
    void unknownReservationPaymentPageReturns404Page() throws Exception {
        mockMvc.perform(get("/reservations/{no}/payment", "NO-SUCH-RESERVATION").with(user(owner)))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("본인 예매 결제 페이지는 정상 노출 — 에러 처리가 정상 경로를 막지 않는다")
    void ownerCanOpenPaymentPage() throws Exception {
        mockMvc.perform(get("/reservations/{no}/payment", reservationNo).with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(view().name("reservation/payment"));
    }

    /* ------------------------------------------------------------------
     *  API(@RestController) — JSON 으로 나가야 한다
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("남의 예매 취소 API -> 403 JSON (종전 400)")
    void cancelOtherMembersReservationReturns403Json() throws Exception {
        mockMvc.perform(post("/api/reservations/{no}/cancel", reservationNo)
                        .with(user(stranger)).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인 예매만 취소할 수 있습니다."));
    }

    @Test
    @DisplayName("없는 예매번호 취소 API -> 404 JSON")
    void cancelUnknownReservationReturns404Json() throws Exception {
        mockMvc.perform(post("/api/reservations/{no}/cancel", "NO-SUCH-RESERVATION")
                        .with(user(owner)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("좌석 id 타입 불일치 -> 400 JSON")
    void badSeatIdReturns400Json() throws Exception {
        mockMvc.perform(post("/api/seats/{id}/hold", "abc").with(user(owner)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * catch-all {@code @ExceptionHandler(Exception.class)} 를 넣으면 스프링 MVC 가
     * 던지는 {@code NoResourceFoundException} 까지 삼켜서 없는 주소가 500 으로 나간다.
     * {@code ErrorResponse} 핸들러가 그걸 막고 있는지 확인한다.
     */
    @Test
    @DisplayName("매핑 없는 주소 -> 404 (500 으로 새지 않는다)")
    void unmappedUrlIsNotSwallowedAs500() throws Exception {
        mockMvc.perform(get("/definitely-not-a-real-page").with(user(owner)))
                .andExpect(status().isNotFound());
    }
}
