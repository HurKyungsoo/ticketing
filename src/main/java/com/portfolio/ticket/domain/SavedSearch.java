package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 목록 화면에서 저장해 둔 검색 조건. 값이 null 인 축은 "전체"를 뜻한다 — {@link
 * com.portfolio.ticket.mapper.dto.PerformanceFilter} 와 같은 규칙이다.
 *
 * <p>요일·시간대·공연장은 담지 않는다. 새 공연 매칭(아래 {@link #matches})은 "무엇을
 * 보고 싶은가"만 보고, "언제 갈 수 있는가"는 회차가 실제로 잡힌 뒤에나 의미가 있는
 * 축이라 등록 시점 매칭과는 안 맞는다.
 */
@Entity
@Table(
    name = "saved_search",
    indexes = @Index(name = "idx_saved_search_member", columnList = "member_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SavedSearch {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 마이페이지 목록에 보여줄 이름. 조건에서 자동으로 만든다({@link #labelFrom}). */
    @Column(nullable = false, length = 100)
    private String label;

    /** {@link PerformanceCategory#name()}. null 이면 전체 장르. */
    @Column(length = 30)
    private String category;

    /** null 이면 전국. */
    @Column(length = 50)
    private String region;

    /**
     * 1~12. null 이면 월 전체.
     *
     * <p>컬럼명은 {@code search_month} 다 — {@code month} 는 H2/MariaDB 예약어(MONTH() 함수)라
     * DDL 이 구문 오류를 낸다(V9 마이그레이션 주석 참고). 자바 필드·getter(getMonth())는
     * 그대로 두고 매핑만 옮긴다 — 도메인 이름까지 바꿀 이유는 없다.
     */
    @Column(name = "search_month")
    private Integer month;

    /** null 이면 검색어 없음. */
    @Column(length = 100)
    private String keyword;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 조건에서 사람이 읽을 이름을 만든다. "서울 · 뮤지컬 · 9월" 처럼 값이 있는 축만
     * " · " 로 잇는다. 아무 조건도 없으면(전체 검색을 저장한 경우) "전체 공연"으로 둔다 —
     * 빈 문자열은 목록에서 이름 없는 행처럼 보인다.
     */
    public static String labelFrom(PerformanceCategory category, String region, Integer month, String keyword) {
        List<String> parts = new ArrayList<>();
        if (region != null && !region.isBlank()) parts.add(region);
        if (category != null) parts.add(category.label());
        if (month != null) parts.add(month + "월");
        if (keyword != null && !keyword.isBlank()) parts.add("'" + keyword + "'");
        return parts.isEmpty() ? "전체 공연" : String.join(" · ", parts);
    }

    /**
     * 새로 등록된 공연이 이 저장 검색과 맞는지. 축마다 값이 있으면 정확히 맞아야 하고
     * (null 이면 그 축은 건너뛴다), 전부 통과해야 매칭이다 — 목록 필터가 여러 축을
     * AND 로 좁히는 것과 같은 규칙이다.
     *
     * <p>월은 공연 자체가 아니라 <b>회차</b>를 본다({@code schedules} 중 하나라도 그 달이면
     * 통과) — 목록의 월 필터가 "그 달에 열리는 회차가 있는 공연"을 찾는 것과 같은 기준을
     * 쓴다. 공연 기간(start~end)만 보면 8개월짜리 장기 공연이 아무 달에나 걸린다.
     *
     * @param schedules 이 공연의 회차 전체. 월 조건이 없으면 안 봐도 되지만, 호출부가
     *                  이미 들고 있는 값을 그대로 받는다(축마다 다시 조회하지 않는다).
     */
    public boolean matches(Performance performance, List<PerformanceSchedule> schedules) {
        if (category != null && (performance.getCategory() == null
                || !category.equals(performance.getCategory().name()))) {
            return false;
        }
        if (region != null && !region.equals(performance.getRegion())) {
            return false;
        }
        if (keyword != null && !keyword.isBlank()
                && (performance.getTitle() == null
                    || !performance.getTitle().toLowerCase().contains(keyword.toLowerCase()))) {
            return false;
        }
        if (month != null && schedules.stream()
                .noneMatch(s -> s.getShowAt().getMonthValue() == month)) {
            return false;
        }
        return true;
    }
}
