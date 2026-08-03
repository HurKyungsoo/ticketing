package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.PerformanceRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 필터 중 <b>회차 축</b>(월 · 요일 · 시간대)의 조합 의미를 고정한다.
 *
 * <p>셋은 반드시 <i>같은 회차 한 행</i>에 걸려야 한다. 예전에는 월이
 * {@code performance.start_date} 기준이라 축이 달랐고, 그래서 "8월 + 주말" 이
 * <i>8월에 개막했고 (9월이든 언제든) 주말 회차가 있는</i> 공연까지 걸러냈다.
 * 예외도 에러도 안 나고 결과만 조용히 틀리는 종류라 테스트로 못 박는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerformanceFilterTest {

    @Autowired PerformanceListService listService;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    // 2026-08-01(토) · 08-03(월) · 09-05(토) — 요일을 실제 달력으로 확인한 값
    private static final LocalDateTime AUG_SAT = LocalDateTime.of(2026, 8, 1, 19, 0);
    private static final LocalDateTime AUG_MON = LocalDateTime.of(2026, 8, 3, 19, 0);
    private static final LocalDateTime SEP_SAT = LocalDateTime.of(2026, 9, 5, 19, 0);

    /**
     * 삭제 순서는 FK 방향을 따른다. <b>예매를 빼먹으면 안 된다</b> — 테스트들이 같은
     * 인메모리 DB(DB_CLOSE_DELAY=-1)를 공유해서, 다른 테스트가 남긴 reservation 이
     * schedule 을 참조한 채로 남아 있으면 여기서 FK 위반이 난다.
     * (이 테스트만 단독으로 돌리면 남은 예매가 없어 통과해버리므로 놓치기 쉽다.)
     */
    @BeforeEach
    void setUp() {
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    private void createPerformance(String title, LocalDateTime... showAts) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("FILTER-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                // 시작일은 일부러 전부 8월로 맞춘다 — 월 필터가 start_date 가 아니라
                // 회차를 본다는 걸 확인하려면 start_date 로는 구분이 안 돼야 한다.
                .startDate(LocalDate.of(2026, 8, 1))
                // 종료일만은 "오늘"에 상대적으로 잡는다. 고정 날짜(2026-09-30)로 두면
                // 그 날이 지나는 순간 이 공연들이 종료작이 되어, status=ongoing 을 쓰는
                // 테스트가 어느 날 갑자기 깨진다.
                .endDate(LocalDate.now().plusYears(1))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());

        for (LocalDateTime showAt : showAts) {
            scheduleRepository.save(PerformanceSchedule.builder()
                    .performance(performance)
                    .showAt(showAt)
                    .totalSeats(100)
                    .remainingSeats(100)
                    .build());
        }
    }

    /** 종료일을 직접 정해야 하는 진행 현황(status) 테스트용. */
    private void createPerformanceEndingOn(String title, LocalDate endDate) {
        performanceRepository.save(Performance.builder()
                .externalId("FILTER-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(endDate.minusDays(10))
                .endDate(endDate)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    /** status 는 "전체"로 둔다 — 기본값(ongoing)은 오늘 기준이라 테스트 날짜에 좌우된다. */
    private List<String> titlesOf(Integer month, String dayOfWeek) {
        return titlesOf(month, dayOfWeek, "ALL");
    }

    private List<String> titlesOf(Integer month, String dayOfWeek, String status) {
        return listService.search(null, month, dayOfWeek, null, status, null, null, null, null, 0)
                .performances().stream()
                .map(r -> r.getTitle())
                .toList();
    }

    /** 정렬만 바꿔가며 제목 순서를 본다. status 는 "전체" — 종료작도 정렬 대상이다. */
    private List<String> titlesSortedBy(String sort) {
        return listService.search(null, null, null, null, "ALL", null, null, null, sort, 0)
                .performances().stream()
                .map(r -> r.getTitle())
                .toList();
    }

    @DisplayName("요일 필터 — 주말/주중/개별 요일이 회차 요일로 걸린다")
    @Test
    void filtersByDayOfWeek() {
        createPerformance("토요일공연", AUG_SAT);
        createPerformance("월요일공연", AUG_MON);

        assertThat(titlesOf(null, "weekend")).containsExactly("토요일공연");
        assertThat(titlesOf(null, "weekday")).containsExactly("월요일공연");
        assertThat(titlesOf(null, "sat")).containsExactly("토요일공연");
        assertThat(titlesOf(null, "mon")).containsExactly("월요일공연");
        assertThat(titlesOf(null, null)).hasSize(2);
    }

    @DisplayName("월 필터는 시작일이 아니라 회차가 열리는 달을 본다")
    @Test
    void monthFilterLooksAtSchedulesNotStartDate() {
        // 둘 다 start_date 는 8/1 이지만 회차는 각각 8월 / 9월에만 있다.
        createPerformance("8월회차", AUG_SAT);
        createPerformance("9월회차", SEP_SAT);

        assertThat(titlesOf(8, null)).containsExactly("8월회차");
        assertThat(titlesOf(9, null)).containsExactly("9월회차");
    }

    /**
     * 이번 수정의 핵심. "9월주말만" 은 8월에도 회차가 있지만 그 8월 회차는 <b>월요일</b>이다.
     * 월과 요일을 따로 걸면(예전 방식) 8월 조건과 주말 조건이 서로 다른 회차에서 충족되어
     * 이 공연이 "8월 + 주말" 에 잘못 걸린다.
     */
    @DisplayName("월 + 요일은 같은 회차에 걸려야 한다 — 8월 주말에 8월 평일 공연이 섞이면 안 된다")
    @Test
    void monthAndDayOfWeekMustMatchSameSchedule() {
        createPerformance("8월주말있음", AUG_SAT, SEP_SAT);
        createPerformance("9월주말만", AUG_MON, SEP_SAT);   // 8월 회차는 월요일뿐

        List<String> augWeekend = titlesOf(8, "weekend");

        assertThat(augWeekend)
                .as("8월의 '주말' 회차가 실제로 있는 공연만 나와야 한다")
                .containsExactly("8월주말있음");

        // 각 조건을 따로 걸면 둘 다 걸린다는 것도 같이 고정한다 —
        // 이게 참이어야 위 단언이 "축을 묶었기 때문에" 통과한 것이 된다.
        assertThat(titlesOf(8, null)).hasSize(2);
        assertThat(titlesOf(null, "weekend")).hasSize(2);
    }

    /* ------------------------------------------------------------------
     *  진행 현황(status)
     *
     *  실데이터로는 검증할 수 없는 필터다 — PerformanceSyncService 가 수집 단계에서
     *  이미 종료된 공연을 걸러내서 DB 에 종료작이 아예 없다. 그래서 "진행·예정작" 을
     *  골라도 전체와 건수가 같고, 필터가 걸린 건지 무시된 건지 화면으로는 구분되지 않는다.
     *  종료일을 직접 정한 데이터로 여기서 확인한다.
     * ------------------------------------------------------------------ */

    @DisplayName("진행 현황 — 오늘 끝나는 공연은 '진행·예정작'에 포함된다(경계값)")
    @Test
    void statusFilterSplitsByEndDate() {
        LocalDate today = LocalDate.now();
        createPerformanceEndingOn("어제끝남", today.minusDays(1));
        createPerformanceEndingOn("오늘끝남", today);
        createPerformanceEndingOn("내일끝남", today.plusDays(1));

        // 쿼리 파라미터는 소문자로 들어온다. 매퍼는 대문자로만 비교하므로 서비스가
        // 대문자로 바꿔주는데, 그게 빠지면 조건이 통째로 사라져 필터가 조용히 무시된다
        // (README 5번에 기록된 실제 버그 — 세 값이 전부 같은 건수를 내놓았다).
        assertThat(titlesOf(null, null, "ongoing"))
                .containsExactlyInAnyOrder("오늘끝남", "내일끝남");
        assertThat(titlesOf(null, null, "ended"))
                .containsExactly("어제끝남");
    }

    @DisplayName("진행 현황 — 세 값이 서로 다른 결과를 내야 한다(무시되면 전부 같아진다)")
    @Test
    void statusFilterIsNotSilentlyIgnored() {
        LocalDate today = LocalDate.now();
        createPerformanceEndingOn("종료작", today.minusDays(5));
        createPerformanceEndingOn("진행작", today.plusDays(5));

        List<String> all = titlesOf(null, null, null);       // 필터 없음
        List<String> ongoing = titlesOf(null, null, "ongoing");
        List<String> ended = titlesOf(null, null, "ended");

        assertThat(all).hasSize(2);
        assertThat(ongoing).containsExactly("진행작");
        assertThat(ended).containsExactly("종료작");
        assertThat(ongoing).isNotEqualTo(all);
        assertThat(ended).isNotEqualTo(all);
    }

    @DisplayName("진행 현황은 다른 필터와 함께 걸린다 — 요일과 조합해도 둘 다 적용")
    @Test
    void statusCombinesWithOtherFilters() {
        LocalDate today = LocalDate.now();
        // 회차까지 있어야 요일 조건이 의미를 갖는다. 종료일만 다르게 둔다.
        createPerformance("진행_토요일", AUG_SAT);
        createPerformanceEndingOn("종료_회차없음", today.minusDays(5));

        assertThat(titlesOf(null, "weekend", "ongoing"))
                .as("진행 중이면서 주말 회차가 있는 공연만")
                .containsExactly("진행_토요일");
        assertThat(titlesOf(null, "weekend", "ended"))
                .as("종료작 중에는 주말 회차를 가진 공연이 없다")
                .isEmpty();
    }

    /* ------------------------------------------------------------------
     *  정렬(sort)
     *
     *  정렬은 건수가 안 바뀌어서 화면만 봐서는 걸린 건지 무시된 건지 알 수 없다.
     *  필터가 조용히 무시되던 것과 같은 종류의 결함이라 여기서 순서를 못 박는다.
     * ------------------------------------------------------------------ */

    /** 종료일·가격을 직접 정해야 하는 정렬 테스트용. 회차는 만들지 않는다(정렬 축이 공연 컬럼이라 불필요). */
    private void createPerformanceForSort(String title, LocalDate endDate, Integer basePrice) {
        performanceRepository.save(Performance.builder()
                .externalId("SORT-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(endDate.minusDays(10))
                .endDate(endDate)
                .totalSeatCount(100)
                .basePrice(basePrice)
                .build());
    }

    @DisplayName("마감 임박순 — 종료일이 가까운 공연이 먼저 온다")
    @Test
    void sortsByClosingSoon() {
        LocalDate today = LocalDate.now();
        createPerformanceForSort("늦게끝남", today.plusDays(30), 50_000);
        createPerformanceForSort("곧끝남", today.plusDays(2), 50_000);
        createPerformanceForSort("중간", today.plusDays(10), 50_000);

        assertThat(titlesSortedBy("closing")).containsExactly("곧끝남", "중간", "늦게끝남");
    }

    @DisplayName("낮은 가격순 — 가격 미상(null)은 맨 뒤로 간다")
    @Test
    void sortsByPriceWithNullsLast() {
        LocalDate end = LocalDate.now().plusDays(30);
        createPerformanceForSort("비쌈", end, 90_000);
        createPerformanceForSort("가격미상", end, null);
        createPerformanceForSort("쌈", end, 10_000);

        // NULL 순서는 H2 와 MariaDB 의 기본 동작이 서로 달라서(MariaDB 는 ASC 에서 NULL 이 앞)
        // 매퍼가 CASE 로 직접 뒤로 보낸다. 그게 빠지면 DB 를 바꾸는 순간 순서가 뒤집힌다.
        assertThat(titlesSortedBy("priceAsc")).containsExactly("쌈", "비쌈", "가격미상");
    }

    @DisplayName("최신 등록순 — 나중에 수집된 공연이 먼저 온다")
    @Test
    void sortsByNewest() {
        LocalDate end = LocalDate.now().plusDays(30);
        createPerformanceForSort("먼저등록", end, 50_000);
        createPerformanceForSort("나중등록", end, 50_000);

        assertThat(titlesSortedBy("newest")).containsExactly("나중등록", "먼저등록");
    }

    /**
     * 정렬 코드는 매퍼에서 {@code <choose>} 로만 비교되고 SQL 에 이어붙지 않는다.
     * {@code ${}} 로 바꾸면 이 입력이 그대로 ORDER BY 에 들어가 쿼리가 깨지거나 주입이 뚫린다.
     */
    @DisplayName("알 수 없는 정렬값은 기본 정렬로 떨어진다 (SQL 로 새어나가지 않는다)")
    @Test
    void unknownSortFallsBackToDefault() {
        LocalDate end = LocalDate.now().plusDays(30);
        createPerformanceForSort("가", end, 50_000);
        createPerformanceForSort("나", end, 50_000);

        List<String> injected = titlesSortedBy("p.id; DROP TABLE performance");
        assertThat(injected).containsExactlyElementsOf(titlesSortedBy("recommended"));
        assertThat(performanceRepository.count()).as("테이블이 살아 있어야 한다").isEqualTo(2);
    }

    /**
     * 정렬 키가 같은 행들의 순서가 쿼리마다 흔들리면 LIMIT/OFFSET 페이징이 어긋나
     * 같은 공연이 두 페이지에 나오거나 아예 빠진다. 매퍼가 모든 갈래 끝에 p.id 를
     * 붙여 순서를 고정하는데, 그걸 빼면 이 테스트가 (DB 기분에 따라) 깨진다.
     */
    @DisplayName("정렬 키가 전부 같아도 페이징에 중복·누락이 없다")
    @Test
    void paginationIsStableWhenSortKeysTie() {
        LocalDate end = LocalDate.now().plusDays(30);
        int total = 15;   // PAGE_SIZE(12) 를 넘겨 2페이지가 되도록
        for (int i = 0; i < total; i++) {
            createPerformanceForSort("동점" + i, end, 50_000);   // 종료일·가격이 전부 같다
        }

        List<String> merged = new java.util.ArrayList<>();
        merged.addAll(pageTitles("closing", 0));
        merged.addAll(pageTitles("closing", 1));

        assertThat(merged).as("중복 없이 전부 한 번씩").doesNotHaveDuplicates().hasSize(total);
    }

    private List<String> pageTitles(String sort, int page) {
        return listService.search(null, null, null, null, "ALL", null, null, null, sort, page)
                .performances().stream()
                .map(r -> r.getTitle())
                .toList();
    }

    @DisplayName("월별 건수도 회차 기준 — 요일을 걸면 그 요일 회차가 있는 달만 세어진다")
    @Test
    void monthFacetCountsFollowScheduleAxis() {
        createPerformance("8월주말있음", AUG_SAT, SEP_SAT);
        createPerformance("9월주말만", AUG_MON, SEP_SAT);

        var months = listService.search(null, null, "weekend", null, "ALL", null, null, null, null, 0).months();

        long aug = months.stream().filter(o -> "8".equals(o.value())).mapToLong(PerformanceListService.Option::count).sum();
        long sep = months.stream().filter(o -> "9".equals(o.value())).mapToLong(PerformanceListService.Option::count).sum();

        assertThat(aug).as("8월 주말 회차가 있는 공연은 1건").isEqualTo(1);
        assertThat(sep).as("9월 주말 회차가 있는 공연은 2건").isEqualTo(2);
    }
}
