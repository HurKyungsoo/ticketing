package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
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
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 화면 표기 로케일 고정.
 *
 * <p><b>배포본에서 실제로 틀린 값이 나가고 있었다.</b> 영어권 브라우저로 들어오면 요일이
 * 「8/26(수)」가 아니라 「8/26(Wed)」로 나갔다 — 실측:
 * <pre>
 *   Accept-Language: ko-KR  →  8/26(수) 16:00
 *   Accept-Language: en-US  →  8/26(Wed) 16:00
 * </pre>
 *
 * <p>원인은 Thymeleaf 의 {@code #temporals.format} 이 <b>요청 로케일</b>을 쓴다는 것이다.
 * 자바 쪽은 이미 {@code Locale.KOREAN} 을 박아 뒀는데({@code PerformanceListRow.NEXT_SHOW},
 * {@code Notification}, {@code ReservationMailSender}) 템플릿 8곳이 빠져 있었다. 그래서
 * 같은 화면 안에서도 카드는 「(수)」, 상세는 「(Wed)」로 갈렸다.
 *
 * <p>{@code HomePageTest.weekdayIsAlwaysKorean} 이 이미 있었지만 그건 DTO 하나만 본다 —
 * <b>테스트가 있다는 사실이 오히려 "처리됐다"는 착각을 만들었다.</b> 그래서 여기서는 DTO 가
 * 아니라 <b>서버가 실제로 그린 HTML</b>을 영어 요청으로 받아서 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FixedKoreanLocaleTest {

    /** 한국어 요일 한 글자. 이 중 하나가 아니면 로케일이 새어 들어온 것이다. */
    private static final String KOREAN_WEEKDAYS = "월화수목금토일";

    @Autowired MockMvc mockMvc;
    @Autowired LocaleResolver localeResolver;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private Long performanceId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("LOCALE-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("로케일 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        performanceId = performance.getId();

        scheduleId = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(5).withHour(19).withMinute(30).withSecond(0).withNano(0))
                .totalSeats(100)
                .remainingSeats(100)
                .build()).getId();
    }

    /**
     * 이 테스트가 이번 수정의 핵심이다. 영어 요청으로 <b>서버가 그린 HTML</b>을 받아
     * 요일이 한국어인지 본다 — 종전 코드에서는 여기가 「(Wed)」로 나왔다.
     */
    @DisplayName("영어 브라우저로 들어와도 공연 상세의 요일은 한국어다")
    @Test
    void detailKeepsKoreanWeekdayForEnglishRequest() throws Exception {
        String html = mockMvc.perform(get("/performances/{id}", performanceId)
                        .locale(Locale.US)
                        .header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 회차 목록의 요일 칩. 종전에는 이 자리가 (Wed) 였다.
        Matcher m = Pattern.compile("class=\"sd-dow\">\\(([^)]+)\\)").matcher(html);
        assertThat(m.find()).as("회차 요일이 렌더링됐어야 한다").isTrue();
        String weekday = m.group(1);
        assertThat(KOREAN_WEEKDAYS)
                .as("영어 요청인데 요일이 '%s' 로 나왔다", weekday)
                .contains(weekday);
    }

    /**
     * 좌석도 헤더의 부제(「공연명 · 8/26(수) 16:00」)는 <b>지금 어느 회차를 고르는 중인지
     * 알려주는 유일한 표시</b>라 다른 화면과 달리 좁은 폭에서도 안 감춘다. 여기가 영어로
     * 나가면 좌석을 고르는 내내 그 표기를 보게 된다.
     */
    @DisplayName("영어 브라우저로 들어와도 좌석도 헤더의 요일은 한국어다")
    @Test
    void seatMapHeaderKeepsKoreanWeekdayForEnglishRequest() throws Exception {
        String html = mockMvc.perform(get("/schedules/{id}/seats", scheduleId)
                        .locale(Locale.US)
                        .header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher m = Pattern.compile("class=\"tag\">[^<]*?\\(([^)]{1,3})\\)").matcher(html);
        assertThat(m.find()).as("좌석도 부제가 렌더링됐어야 한다").isTrue();
        String weekday = m.group(1);
        assertThat(KOREAN_WEEKDAYS)
                .as("영어 요청인데 좌석도 부제 요일이 '%s' 로 나왔다", weekday)
                .contains(weekday);
    }

    /**
     * 위 둘은 템플릿 두 곳만 본다. 실제로 고친 것은 <b>전역 리졸버</b>라, 나머지 여섯 곳
     * (결제 · 결제완료 · 실패 · 환불 · 예매 내역 · 상세의 가장 빠른 회차)까지 한꺼번에
     * 덮인다. 그 근본 보장을 여기서 못 박는다 — {@code locale} 만 주고
     * {@code locale-resolver: fixed} 를 빠뜨리면 Accept-Language 가 다시 이긴다.
     */
    @DisplayName("로케일 리졸버가 ko_KR 로 고정돼 있다 — 요청이 못 이긴다")
    @Test
    void localeResolverIsFixedToKorean() {
        assertThat(localeResolver)
                .as("locale-resolver: fixed 가 빠지면 Accept-Language 가 이긴다")
                .isInstanceOf(FixedLocaleResolver.class);
        assertThat(localeResolver.resolveLocale(null)).isEqualTo(Locale.KOREA);
    }
}
