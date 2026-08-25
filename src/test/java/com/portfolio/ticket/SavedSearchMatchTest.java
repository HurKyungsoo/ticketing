package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SavedSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #2. 저장한 검색 조건과 새로 등록된 공연의 매칭 규칙(SavedSearch.matches).
 *
 * <p>DB 없이 순수 로직만 본다 — 매칭은 이미 메모리에 있는 Performance/PerformanceSchedule
 * 값만으로 결정되고, 배치가 이 판정을 어떻게 부르는지는 별도(SavedSearchNotificationTest)에서 본다.
 */
class SavedSearchMatchTest {

    private Performance performance(PerformanceCategory category, String region, String title) {
        return Performance.builder()
                .externalId("MATCH-" + System.nanoTime())
                .title(title)
                .category(category)
                .region(region)
                .venue("테스트홀")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(30))
                .build();
    }

    private PerformanceSchedule scheduleAt(Performance performance, LocalDateTime showAt) {
        return PerformanceSchedule.builder()
                .performance(performance)
                .showAt(showAt)
                .totalSeats(100)
                .remainingSeats(100)
                .build();
    }

    private SavedSearch search(PerformanceCategory category, String region, Integer month, String keyword) {
        return SavedSearch.builder()
                .memberId(1L)
                .label(SavedSearch.labelFrom(category, region, month, keyword))
                .category(category == null ? null : category.name())
                .region(region)
                .month(month)
                .keyword(keyword)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @DisplayName("축이 전부 null이면(전체 검색) 어떤 공연이든 맞는다")
    @Test
    void emptyCriteriaMatchesEverything() {
        SavedSearch all = search(null, null, null, null);
        Performance p = performance(PerformanceCategory.PLAY, "부산광역시", "아무 공연");

        assertThat(all.matches(p, List.of())).isTrue();
    }

    @DisplayName("장르가 다르면 안 맞는다")
    @Test
    void categoryMismatch() {
        SavedSearch musical = search(PerformanceCategory.MUSICAL, null, null, null);
        Performance play = performance(PerformanceCategory.PLAY, "서울특별시", "연극");

        assertThat(musical.matches(play, List.of())).isFalse();
    }

    @DisplayName("지역이 다르면 안 맞는다")
    @Test
    void regionMismatch() {
        SavedSearch seoul = search(null, "서울특별시", null, null);
        Performance busan = performance(PerformanceCategory.MUSICAL, "부산광역시", "뮤지컬");

        assertThat(seoul.matches(busan, List.of())).isFalse();
    }

    @DisplayName("검색어는 제목에 포함돼 있으면 맞는다 (대소문자 무관)")
    @Test
    void keywordIsCaseInsensitiveSubstring() {
        SavedSearch search = search(null, null, null, "hamilton");
        Performance p = performance(PerformanceCategory.MUSICAL, "서울특별시", "뮤지컬 HAMILTON 내한공연");

        assertThat(search.matches(p, List.of())).isTrue();
    }

    @DisplayName("검색어가 제목에 없으면 안 맞는다")
    @Test
    void keywordMismatch() {
        SavedSearch search = search(null, null, null, "hamilton");
        Performance p = performance(PerformanceCategory.MUSICAL, "서울특별시", "레미제라블");

        assertThat(search.matches(p, List.of())).isFalse();
    }

    @DisplayName("월은 공연 기간이 아니라 회차 시각으로 판정한다")
    @Test
    void monthChecksScheduleNotRunPeriod() {
        SavedSearch september = search(null, null, 9, null);
        // 공연 기간은 8~10월에 걸쳐 있지만(month 조건과 겹칠 수 있어 보임) 회차는
        // 전부 8월에만 있다 — "그 달에 열리는 회차가 있는가"를 보는 목록 필터와 같은 기준.
        Performance p = performance(PerformanceCategory.MUSICAL, "서울특별시", "장기공연");
        List<PerformanceSchedule> augustOnly = List.of(
                scheduleAt(p, LocalDateTime.of(2026, 8, 10, 19, 0)),
                scheduleAt(p, LocalDateTime.of(2026, 8, 20, 19, 0)));

        assertThat(september.matches(p, augustOnly)).isFalse();
    }

    @DisplayName("회차 중 하나라도 그 달이면 월 조건을 통과한다")
    @Test
    void monthMatchesIfAnyScheduleFalls() {
        SavedSearch september = search(null, null, 9, null);
        Performance p = performance(PerformanceCategory.MUSICAL, "서울특별시", "장기공연");
        List<PerformanceSchedule> spanning = List.of(
                scheduleAt(p, LocalDateTime.of(2026, 8, 30, 19, 0)),
                scheduleAt(p, LocalDateTime.of(2026, 9, 1, 19, 0)));

        assertThat(september.matches(p, spanning)).isTrue();
    }

    @DisplayName("여러 축은 AND다 - 하나라도 안 맞으면 전체가 안 맞는다")
    @Test
    void allCriteriaMustMatch() {
        SavedSearch seoulMusical = search(PerformanceCategory.MUSICAL, "서울특별시", null, null);
        Performance busanMusical = performance(PerformanceCategory.MUSICAL, "부산광역시", "뮤지컬");

        assertThat(seoulMusical.matches(busanMusical, List.of())).isFalse();
    }

    @DisplayName("labelFrom은 값이 있는 축만 이어 붙이고, 아무 것도 없으면 전체 공연이라 적는다")
    @Test
    void labelFromJoinsOnlyPresentAxes() {
        assertThat(SavedSearch.labelFrom(PerformanceCategory.MUSICAL, "서울특별시", 9, null))
                .isEqualTo("서울특별시 · 뮤지컬 · 9월");
        assertThat(SavedSearch.labelFrom(null, null, null, null)).isEqualTo("전체 공연");
    }
}
