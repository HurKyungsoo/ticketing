package com.portfolio.ticket;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.MemberRole;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.MemberRepository;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.PerformanceCompareService;
import com.portfolio.ticket.service.PerformanceCompareView;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 공연 비교(이슈 #2 제안 3번).
 *
 * <p>담아둔 목록은 브라우저(localStorage)에 있고 서버는 넘어온 id 로 표만 그린다. 그래서
 * <b>서버가 방어해야 할 것은 전부 "그 id 를 믿을 수 없다"에서 나온다</b> — 담아둔 뒤에
 * 사라진 공연, 손으로 고쳐진 주소, 개수 초과, 중복. 이 테스트의 대부분이 그 경우들이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerformanceCompareTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceCompareService compareService;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired MemberRepository memberRepository;

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

    @DisplayName("담은 순서 그대로 표를 만든다")
    @Test
    void keepsGivenOrder() {
        Long a = performance("가공연", 50_000).getId();
        Long b = performance("나공연", 60_000).getId();

        List<PerformanceCompareView> views = compareService.compare(List.of(b, a), LocalDateTime.now());

        assertThat(views).extracting(PerformanceCompareView::title)
                .containsExactly("나공연", "가공연");
    }

    /**
     * <b>이 프로젝트에서 실제로 일어나는 일이다.</b> 모집 공고 purge
     * ({@code POST /api/admin/performances/purge})가 저장된 공연을 지우므로, 담아둔 id 가
     * 나중에 사라질 수 있다. 404 로 막으면 한 칸 때문에 나머지 두 칸까지 못 본다.
     */
    @DisplayName("사라진 공연은 조용히 빼고 나머지로 표를 만든다")
    @Test
    void skipsMissingPerformances() {
        Long alive = performance("살아있는공연", 50_000).getId();

        List<PerformanceCompareView> views =
                compareService.compare(List.of(alive, 999_999L), LocalDateTime.now());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).title()).isEqualTo("살아있는공연");
    }

    @DisplayName("같은 공연을 두 번 담아도 한 칸만 차지한다")
    @Test
    void foldsDuplicates() {
        Long a = performance("가공연", 50_000).getId();
        Long b = performance("나공연", 60_000).getId();

        List<PerformanceCompareView> views =
                compareService.compare(List.of(a, b, a), LocalDateTime.now());

        assertThat(views).extracting(PerformanceCompareView::title)
                .containsExactly("가공연", "나공연");
    }

    /**
     * 화면(fragment/comparetray)도 3개에서 막지만, 주소를 손으로 고치면 그 상한을 지나
     * 들어올 수 있다. 진짜 상한은 서버가 갖는다.
     */
    @DisplayName("상한을 넘겨 담아도 앞에서부터 상한 개수만 그린다")
    @Test
    void capsAtMaxItems() {
        Long a = performance("1번", 10_000).getId();
        Long b = performance("2번", 20_000).getId();
        Long c = performance("3번", 30_000).getId();
        Long d = performance("4번", 40_000).getId();

        List<PerformanceCompareView> views =
                compareService.compare(List.of(a, b, c, d), LocalDateTime.now());

        assertThat(views).hasSize(PerformanceCompareService.MAX_ITEMS);
        assertThat(views).extracting(PerformanceCompareView::title)
                .containsExactly("1번", "2번", "3번");
    }

    /**
     * 표기가 목록·홈 카드와 같아야 한다. 비교표는 목록에서 고른 공연을 나란히 놓는
     * 화면이라, 같은 공연이 목록에서는 「내일 19:30」인데 비교표에서는
     * 「8/27(목) 19:30」이면 그 자체로 결함이다.
     */
    @DisplayName("다음 회차 · 기간 · 예매 상태를 카드와 같은 표기로 적는다")
    @Test
    void usesSameFormattingAsCards() {
        LocalDateTime now = LocalDateTime.now();
        Performance p = performance("표기공연", 50_000);
        // 잔여 3석 — 목록 카드가 「매진임박」 배지를 다는 기준(10석 이하)이다.
        schedule(p, now.plusDays(1).withHour(19).withMinute(30), 3);

        PerformanceCompareView view = compareService.compare(List.of(p.getId()), now).get(0);

        assertThat(view.nextShow()).isNotNull();
        assertThat(view.nextShow().text()).isEqualTo("내일 19:30");
        assertThat(view.nextShow().soon()).isTrue();
        assertThat(view.availability()).isNotNull();
        assertThat(view.availability().label()).isEqualTo("매진임박");
        assertThat(view.availability().cssClass()).isEqualTo("avail-low");
        assertThat(view.runPeriod()).isNotBlank();
        // 좌석이 없으면 수집 원본의 basePrice 로 대체한다(상세 요약 카드와 같은 규칙).
        assertThat(view.lowestPrice()).isEqualTo(50_000);
    }

    /**
     * 남은 회차가 없는 공연. 예매 화면에 보내 봐야 "예매 가능한 회차가 없습니다" 만
     * 만나므로 화면이 CTA 를 상세 보기로 바꾼다 — 그 스위치가 이 값이다.
     */
    @DisplayName("남은 회차가 없으면 예매로 보내지 않는다")
    @Test
    void notBookableWhenNoUpcomingSchedule() {
        LocalDateTime now = LocalDateTime.now();
        Performance p = performance("지난공연", 50_000);
        schedule(p, now.minusDays(2).withHour(19).withMinute(0), 100);

        PerformanceCompareView view = compareService.compare(List.of(p.getId()), now).get(0);

        assertThat(view.bookable()).isFalse();
        assertThat(view.upcomingCount()).isZero();
        assertThat(view.nextShow()).as("남은 회차가 없으면 적을 회차도 없다").isNull();
    }

    @DisplayName("/performances/compare 는 비교 화면을 그린다")
    @Test
    void rendersComparePage() throws Exception {
        Long a = performance("가공연", 50_000).getId();
        Long b = performance("나공연", 60_000).getId();

        mockMvc.perform(get("/performances/compare").param("ids", a + "," + b))
                .andExpect(status().isOk())
                .andExpect(view().name("performance/compare"))
                .andExpect(model().attributeExists("items"));
    }

    /**
     * 이 주소는 그대로 남에게 보낼 수 있어야 해서 손으로 고쳐질 수 있다.
     * {@code List<Long>} 로 받으면 숫자가 아닌 조각 하나에 400 이 나간다 — 공공데이터
     * 파싱과 같은 판단으로, 못 읽는 조각만 버리고 나머지로 화면을 만든다.
     */
    @DisplayName("주소가 손으로 고쳐져도 못 읽는 조각만 버린다")
    @Test
    void ignoresUnparsableIds() throws Exception {
        Long a = performance("가공연", 50_000).getId();

        mockMvc.perform(get("/performances/compare").param("ids", a + ",abc,"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("items", org.hamcrest.Matchers.hasSize(1)));
    }

    @DisplayName("담은 게 없어도 빈 화면을 그린다 — 오류가 아니다")
    @Test
    void emptyIdsRenderEmptyState() throws Exception {
        mockMvc.perform(get("/performances/compare"))
                .andExpect(status().isOk())
                .andExpect(view().name("performance/compare"))
                .andExpect(model().attribute("items", org.hamcrest.Matchers.hasSize(0)));
    }

    /**
     * {@code /performances/compare} 가 {@code /performances/{id}} 에 먹히면 "compare" 를
     * Long 으로 못 바꿔 400 이 난다. 스프링은 리터럴 경로를 먼저 고르지만, 매핑이 하나
     * 늘 때마다 조용히 뒤집힐 수 있는 자리라 못 박아 둔다.
     */
    @DisplayName("비교 주소가 공연 상세 매핑에 먹히지 않는다")
    @Test
    void compareUrlIsNotSwallowedByDetailMapping() throws Exception {
        mockMvc.perform(get("/performances/compare"))
                .andExpect(status().isOk())
                .andExpect(view().name("performance/compare"));
    }

    /**
     * 트레이는 훑는 화면 셋(홈 · 목록 · 찜 목록)에만 들어간다. 조각 하나를 세 템플릿이
     * 나눠 쓰는 구조라, 한 곳에서 깨져도 나머지 둘은 멀쩡해서 눈으로는 놓치기 쉽다.
     *
     * <p>상세에는 <b>일부러 안 넣었다</b> — 좁은 화면의 상세에는 이미 예매 CTA 가 화면
     * 아래에 고정돼 있어(body.page-detail .mobilebar) 트레이와 겹친다. 그 결정이 나중에
     * 조용히 뒤집히지 않게 여기서 같이 못 박는다.
     */
    @DisplayName("비교함 트레이는 훑는 화면 셋에만 들어간다")
    @Test
    void trayRendersOnBrowsingPagesOnly() throws Exception {
        Performance p = performance("트레이공연", 50_000);
        schedule(p, LocalDateTime.now().plusDays(2).withHour(19).withMinute(0), 100);

        CustomUserDetails principal = new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId("compare-tray-" + System.nanoTime())
                .password("{noop}pw")
                .nickname("비교테스터")
                .role(MemberRole.USER)
                .createdAt(LocalDateTime.now())
                .build()));

        assertThat(bodyOf(get("/"))).contains("id=\"ctray\"");
        assertThat(bodyOf(get("/performances"))).contains("id=\"ctray\"");
        assertThat(bodyOf(get("/mypage/wishlist").with(user(principal)))).contains("id=\"ctray\"");

        // 상세에는 담기 버튼만 있고 트레이는 없다.
        String detail = bodyOf(get("/performances/" + p.getId()));
        assertThat(detail).contains("id=\"compareBtn\"");
        assertThat(detail).doesNotContain("id=\"ctray\"");
    }

    private String bodyOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Performance performance(String title, int basePrice) {
        return performanceRepository.save(Performance.builder()
                .externalId("CMP-" + title + "-" + System.nanoTime())
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

    private void schedule(Performance performance, LocalDateTime showAt, int remaining) {
        scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt.withSecond(0).withNano(0))
                .totalSeats(100)
                .remainingSeats(remaining)
                .build());
    }
}
