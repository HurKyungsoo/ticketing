package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.PerformanceRepository;
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
                .endDate(LocalDate.of(2026, 9, 30))
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

    /** status 는 "전체"로 둔다 — 기본값(ongoing)은 오늘 기준이라 테스트 날짜에 좌우된다. */
    private List<String> titlesOf(Integer month, String dayOfWeek) {
        return listService.search(null, month, dayOfWeek, null, "ALL", null, null, null, 0)
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

    @DisplayName("월별 건수도 회차 기준 — 요일을 걸면 그 요일 회차가 있는 달만 세어진다")
    @Test
    void monthFacetCountsFollowScheduleAxis() {
        createPerformance("8월주말있음", AUG_SAT, SEP_SAT);
        createPerformance("9월주말만", AUG_MON, SEP_SAT);

        var months = listService.search(null, null, "weekend", null, "ALL", null, null, null, 0).months();

        long aug = months.stream().filter(o -> "8".equals(o.value())).mapToLong(PerformanceListService.Option::count).sum();
        long sep = months.stream().filter(o -> "9".equals(o.value())).mapToLong(PerformanceListService.Option::count).sum();

        assertThat(aug).as("8월 주말 회차가 있는 공연은 1건").isEqualTo(1);
        assertThat(sep).as("9월 주말 회차가 있는 공연은 2건").isEqualTo(2);
    }
}
