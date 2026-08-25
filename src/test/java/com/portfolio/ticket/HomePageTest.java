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

import java.time.LocalDate;
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
