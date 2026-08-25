package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.service.PerformanceListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.portfolio.ticket.mapper.dto.PerformanceListRow;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 홈(/)과 목록(/performances)의 분리.
 *
 * <p>종전에는 "/" 가 곧 검색결과 그리드였다. 목록을 /performances 로 옮기면서 가장 위험한 건
 * <b>기존 주소가 조건을 잃는 것</b>이다 — "/?genre=MUSICAL" 같은 북마크·외부 링크가 홈으로
 * 떨어지면 예외도 404 도 안 나고 "그냥 다른 화면"이 나온다. 조용히 틀리는 종류라 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomePageTest {

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
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    /** 홈 섹션은 전부 "진행·예정작"만 보므로 종료일을 넉넉히 잡는다. */
    private void createOngoing(String title, PerformanceCategory category) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("HOME-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(category)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());

        // 이번 달 섹션이 회차를 보므로 오늘 기준으로 회차를 하나 만들어 둔다.
        scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(1).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(100)
                .remainingSeats(100)
                .build());
    }

    /**
     * 임박순. 이 정렬이 없을 때 홈의 「N월 공연」이 한 달 내내 같은 여섯 개였다 —
     * 기본 정렬(개막일 오름차순)이 "가장 먼저 개막해서 아직 안 끝난" 장기 공연만
     * 위에 쌓았기 때문이다. 개막일이 이른 순서와 회차가 가까운 순서가 <b>서로 반대가
     * 되도록</b> 데이터를 만들어야 정렬이 실제로 바뀌었는지 알 수 있다.
     */
    @DisplayName("임박순은 개막일이 아니라 다음 회차가 가까운 순으로 세운다")
    @Test
    void upcomingSortsByNextSchedule() {
        // 오래 전에 개막(=기본 정렬 1위)했지만 다음 회차는 가장 멀다.
        withSchedule("장기공연", LocalDate.now().minusDays(90), LocalDateTime.now().plusDays(20));
        withSchedule("어제개막", LocalDate.now().minusDays(1), LocalDateTime.now().plusDays(2));
        withSchedule("오늘개막", LocalDate.now(), LocalDateTime.now().plusDays(9));

        assertThat(titles("recommended")).containsExactly("장기공연", "어제개막", "오늘개막");
        assertThat(titles("upcoming")).containsExactly("어제개막", "오늘개막", "장기공연");
    }

    /**
     * 남은 회차가 없는 공연. 기간 필터에는 걸리지만(그 달에 회차가 있었다) 이제 볼 수는
     * 없으므로 맨 뒤로 보낸다. NULL 순서는 H2 와 MariaDB 가 기본 동작이 달라 매퍼가
     * CASE 로 직접 민다 — 그게 실제로 먹는지 본다.
     */
    @DisplayName("남은 회차가 없는 공연은 임박순에서 맨 뒤로 간다")
    @Test
    void upcomingPushesPastOnlyToTheEnd() {
        withSchedule("지난회차뿐", LocalDate.now().minusDays(10), LocalDateTime.now().minusDays(3));
        withSchedule("다가오는회차", LocalDate.now().minusDays(10), LocalDateTime.now().plusDays(5));

        assertThat(titles("upcoming")).containsExactly("다가오는회차", "지난회차뿐");
    }

    private List<String> titles(String sort) {
        return listService.search(null, null, null, null, "ongoing", null, null, null, sort, 0)
                .performances().stream().map(PerformanceListRow::getTitle).toList();
    }

    /** 개막일과 회차 시각을 따로 정해서 만든다 — 둘이 어긋나야 정렬 차이가 드러난다. */
    private void withSchedule(String title, LocalDate startDate, LocalDateTime showAt) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("SORT-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(startDate)
                .endDate(LocalDate.now().plusDays(60))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt.withNano(0))
                .totalSeats(100)
                .remainingSeats(100)
                .build());
    }

    /**
     * 카드가 적는 날짜. 종전에는 공연 기간(석 달짜리 범위)이라 "언제 가지?"에 답이 안 됐다.
     */
    @DisplayName("카드 날짜는 오늘 이후 가장 가까운 회차를 말한다")
    @Test
    void cardShowsNextSchedule() {
        Performance p = performanceWith("회차둘",
                LocalDateTime.now().plusDays(9).withHour(20).withMinute(0),
                LocalDateTime.now().plusDays(2).withHour(19).withMinute(30));

        PerformanceListRow row = firstRow();

        assertThat(row.getTitle()).isEqualTo("회차둘");
        // 뒤에 넣은 +2일이 나와야 한다 — "먼저 저장한 것"이 아니라 "가장 가까운 것"이다.
        assertThat(row.getNextShowAt().toLocalDate())
                .isEqualTo(LocalDate.now().plusDays(2));
        assertThat(p.getId()).isEqualTo(row.getId());
    }

    /**
     * <b>월 필터가 걸리면 그 달 회차 중에서 골라야 한다.</b> 8월을 보고 있는데 카드에
     * 9월 회차가 적히면 안 된다. 매퍼가 회차 조건을 서브쿼리에도 같이 거는지 보는
     * 테스트라, 전체에서 가장 가까운 회차를 <b>필터 밖</b>에 두는 게 핵심이다.
     */
    @DisplayName("월 필터가 걸리면 그 달의 회차를 말한다")
    @Test
    void cardRespectsMonthFilterWhenPickingNextSchedule() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1).withDayOfMonth(10);
        LocalDate monthAfter = LocalDate.now().plusMonths(2).withDayOfMonth(10);
        performanceWith("두달치",
                nextMonth.atTime(19, 0),      // 전체에서 가장 가깝지만 필터 밖이다
                monthAfter.atTime(19, 0));

        List<PerformanceListRow> rows = listService
                .search(null, monthAfter.getMonthValue(), null, null, "ongoing", null, null, null, null, 0)
                .performances();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getNextShowAt().toLocalDate()).isEqualTo(monthAfter);
    }

    @DisplayName("오늘·내일은 날짜 대신 말로 적는다")
    @Test
    void todayAndTomorrowAreSpelledOut() {
        LocalDate today = LocalDate.of(2026, 8, 25);
        PerformanceListRow row = new PerformanceListRow();

        row.setNextShowAt(today.atTime(19, 30));
        assertThat(row.nextShowLabel(today)).isEqualTo("오늘 19:30");

        row.setNextShowAt(today.plusDays(1).atTime(15, 0));
        assertThat(row.nextShowLabel(today)).isEqualTo("내일 15:00");

        // 남은 회차가 없으면 null — 화면이 그때만 종전대로 기간을 적는다.
        row.setNextShowAt(null);
        assertThat(row.nextShowLabel(today)).isNull();
    }

    /**
     * 요일은 로케일을 안 박으면 JVM 기본을 따라간다. 배포 서버(Ubuntu)는 대개 en_US 라
     * 「8/27(목)」이 「8/27(Thu)」로 나가는데, 한국어 윈도우에서 개발하면 안 드러난다.
     */
    @DisplayName("다음 회차 요일은 서버 로케일과 무관하게 한국어다")
    @Test
    void weekdayIsAlwaysKorean() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            PerformanceListRow row = new PerformanceListRow();
            row.setNextShowAt(LocalDateTime.of(2026, 8, 27, 19, 30));

            String label = row.nextShowLabel(LocalDate.of(2026, 8, 25));

            assertThat(label).startsWith("다음 공연 8/27(").endsWith(") 19:30");
            String weekday = label.substring(label.indexOf('(') + 1, label.indexOf(')'));
            assertThat("월화수목금토일").contains(weekday);
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * 기간 표기. 다음 회차만 적으면 "이 공연이 언제까지 하는지"를 알 수 없어서 두 줄로
     * 나눠 놓았는데, 그 뒷줄이 이 값이다.
     *
     * <p>연도는 바뀔 때만 적는다. 종전 {@code 2026.06.04 – 2026.08.30} 은 23자에 연도가
     * 두 번 들어가 카드 폭에서 잘렸다.
     */
    @DisplayName("공연 기간은 연도가 바뀔 때만 연도를 적는다")
    @Test
    void runPeriodElidesRedundantYear() {
        LocalDate today = LocalDate.of(2026, 8, 25);

        // 둘 다 올해 — 연도를 아예 안 적는다
        assertThat(PerformanceListRow.periodFor(
                LocalDate.of(2026, 6, 4), LocalDate.of(2026, 8, 30), today))
                .isEqualTo("6.4 – 8.30");

        // 해를 넘긴다 — 양쪽에 적는다
        assertThat(PerformanceListRow.periodFor(
                LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 5), today))
                .isEqualTo("2026.12.20 – 2027.1.5");

        /*
         * 같은 해지만 올해가 아닌 경우. "같은 해면 생략"으로 두면 여기서 3.1 – 4.1 이 되어
         * 올해 공연처럼 읽힌다 — 12월에 내년 공연을 보는 흔한 상황이다. 앞에만 적는다.
         */
        assertThat(PerformanceListRow.periodFor(
                LocalDate.of(2027, 3, 1), LocalDate.of(2027, 4, 1), today))
                .isEqualTo("2027.3.1 – 4.1");
    }

    private PerformanceListRow firstRow() {
        return listService.search(null, null, null, null, "ongoing", null, null, null, null, 0)
                .performances().get(0);
    }

    /** 회차를 원하는 시각으로 여러 개 달아 만든다. */
    private Performance performanceWith(String title, LocalDateTime... showAts) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("NEXT-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusMonths(3))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
        for (LocalDateTime showAt : showAts) {
            scheduleRepository.save(PerformanceSchedule.builder()
                    .performance(performance)
                    .showAt(showAt.withSecond(0).withNano(0))
                    .totalSeats(100)
                    .remainingSeats(100)
                    .build());
        }
        return performance;
    }

    @DisplayName("홈은 섹션과 장르 칩을 채워서 내려준다")
    @Test
    void homeReturnsSections() {
        createOngoing("뮤지컬하나", PerformanceCategory.MUSICAL);
        createOngoing("연극하나", PerformanceCategory.PLAY);

        PerformanceListService.Home home = listService.home();

        assertThat(home.total()).isEqualTo(2);
        assertThat(home.sections()).isNotEmpty();
        assertThat(home.sections()).allSatisfy(s -> {
            assertThat(s.title()).isNotBlank();
            // "전체 보기"가 목록 화면을 가리켜야 한다. 홈으로 되돌아가면 순환이 된다.
            assertThat(s.moreUrl()).startsWith("/performances?");
        });
        assertThat(home.categories()).anySatisfy(c ->
                assertThat(c.label()).isEqualTo("뮤지컬"));
    }

    /**
     * 밴드 묶기. 화면이 몇 초 간격으로 돌리는 단위라, 여기서 이가 빠진 묶음이 나오면
     * 3열 그리드 오른쪽이 뚫린 채로 몇 초 서 있게 된다.
     */
    @DisplayName("밴드는 묶음 크기로 끊고, 모자란 마지막 묶음은 버린다")
    @Test
    void bandGroupsDropPartialTail() {
        PerformanceListService.HomeSection section = sectionOf(14);

        assertThat(section.groups(6)).hasSize(2);
        assertThat(section.groups(6)).allSatisfy(g -> assertThat(g).hasSize(6));
        // 남은 두 건은 버린다 — 두 장짜리 묶음은 "공연이 둘뿐"이 아니라 깨진 화면으로 읽힌다.
        assertThat(section.groups(6).stream().mapToInt(java.util.List::size).sum()).isEqualTo(12);
    }

    /**
     * 수집 직후나 로컬 시드처럼 한 묶음도 못 채울 때. 여기서 빈 목록을 내면 화면이
     * 섹션을 통째로 감춰서, 공연이 있는데도 홈 가운데가 사라진다.
     */
    @DisplayName("한 묶음도 못 채우면 있는 것만이라도 한 묶음으로 낸다")
    @Test
    void bandKeepsEverythingWhenTooFew() {
        assertThat(sectionOf(4).groups(6)).hasSize(1);
        assertThat(sectionOf(4).groups(6).get(0)).hasSize(4);
        // 아예 없으면 묶음도 없다(화면이 섹션을 감추는 게 맞는 경우다).
        assertThat(sectionOf(0).groups(6)).isEmpty();
    }

    @DisplayName("홈은 밴드 묶음 크기를 같이 내려준다 — 화면이 그 값으로 끊는다")
    @Test
    void homeCarriesBandSize() {
        createOngoing("뮤지컬하나", PerformanceCategory.MUSICAL);

        PerformanceListService.Home home = listService.home();

        // 3열 그리드라 3의 배수여야 마지막 줄이 안 빈다.
        assertThat(home.bandSize()).isEqualTo(6);
        assertThat(home.bandSize() % 3).isZero();
    }

    /** 묶기만 보는 테스트라 행 내용은 필요 없다. */
    private PerformanceListService.HomeSection sectionOf(int count) {
        java.util.List<com.portfolio.ticket.mapper.dto.PerformanceListRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new com.portfolio.ticket.mapper.dto.PerformanceListRow());
        }
        return new PerformanceListService.HomeSection("제목", "부제", "/performances?x", rows);
    }

    @DisplayName("홈 섹션은 종료작을 담지 않는다")
    @Test
    void homeExcludesEndedPerformances() {
        createOngoing("진행작", PerformanceCategory.MUSICAL);
        performanceRepository.save(Performance.builder()
                .externalId("HOME-ended-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("종료작")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(20))
                .endDate(LocalDate.now().minusDays(5))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());

        PerformanceListService.Home home = listService.home();

        assertThat(home.total()).as("총계도 진행·예정작만").isEqualTo(1);
        assertThat(home.sections()).allSatisfy(s ->
                assertThat(s.items()).noneSatisfy(p ->
                        assertThat(p.getTitle()).isEqualTo("종료작")));
    }

    @DisplayName("/ 는 홈을 그린다")
    @Test
    void rootRendersHome() throws Exception {
        createOngoing("뮤지컬하나", PerformanceCategory.MUSICAL);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("performance/home"))
                .andExpect(model().attributeExists("home"));
    }

    /**
     * 이 테스트가 이번 변경의 핵심이다. "/" 가 목록이던 시절의 주소가 조건을 그대로 들고
     * 목록으로 넘어가야 한다 — 파라미터를 흘리면 사용자는 "필터가 안 걸린 다른 화면"을
     * 보게 되고, 에러가 안 나므로 아무도 눈치채지 못한다.
     */
    @DisplayName("옛 주소(/?genre=...)는 조건을 유지한 채 목록으로 넘어간다")
    @Test
    void legacyListUrlRedirectsKeepingParams() throws Exception {
        mockMvc.perform(get("/").param("genre", "MUSICAL").param("dayOfWeek", "weekend"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String location = result.getResponse().getRedirectedUrl();
                    assertThat(location).startsWith("/performances?");
                    assertThat(location).contains("genre=MUSICAL");
                    assertThat(location).contains("dayOfWeek=weekend");
                });
    }

    @DisplayName("한글 검색어도 리다이렉트에서 깨지지 않는다")
    @Test
    void legacyUrlRedirectEncodesKoreanKeyword() throws Exception {
        mockMvc.perform(get("/").param("keyword", "뮤지컬"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String location = result.getResponse().getRedirectedUrl();
                    // 인코딩된 채로 넘어가야 목록이 원래 글자를 되찾는다.
                    assertThat(location).contains("keyword=%EB%AE%A4%EC%A7%80%EC%BB%AC");
                });
    }

    @DisplayName("/performances 는 목록을 그린다")
    @Test
    void performancesRendersList() throws Exception {
        createOngoing("뮤지컬하나", PerformanceCategory.MUSICAL);

        mockMvc.perform(get("/performances"))
                .andExpect(status().isOk())
                .andExpect(view().name("performance/list"))
                .andExpect(model().attributeExists("result"));
    }
}
