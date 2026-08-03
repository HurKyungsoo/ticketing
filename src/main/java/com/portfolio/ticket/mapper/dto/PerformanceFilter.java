package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 공연 목록 필터 조건. 값이 null(또는 빈 문자열)이면 "전체"(필터 없음)를 뜻한다.
 * 쿼리 파라미터의 "all" 센티넬 -> null 변환은 컨트롤러 책임이고, 매퍼는 null 만 안다.
 *
 * <p><b>월 · 요일 · 시간대는 모두 "회차" 축이다.</b> 매퍼가 이 셋을 EXISTS 하나에 묶어
 * <i>같은 회차 한 행</i>에 걸므로, "8월 + 주말 + 저녁" 은 8월의 주말 저녁 회차가 있는
 * 공연만 걸러낸다. 축이 갈려 있으면(예전엔 month 가 performance.start_date 기준이었다)
 * 8월에 개막했고 9월 주말 회차가 있는 공연이 "8월 주말" 로 걸리는 어긋남이 생긴다.
 */
@Getter
@Setter
public class PerformanceFilter {
    private String category;       // PerformanceCategory.name()
    /** 1~12. 공연 시작월이 아니라 <b>회차가 열리는 달</b>이다. */
    private Integer month;
    /**
     * SQL {@code DAYOFWEEK()} 값 목록 (1=일 … 7=토). null/빈 목록이면 요일 필터 없음.
     * "주중"/"주말" 같은 묶음도 결국 이 숫자 목록으로 펼쳐져 들어온다.
     */
    private List<Integer> daysOfWeek;
    private LocalTime timeSlotFrom;
    /**
     * "저녁(18~24시)" 같은 자정 경계는 LocalTime.of(23,59,59) 로 넘길 것 — H2/MySQL 은
     * TIME 리터럴로 "24:00:00" 을 못 받아들여서 문자 그대로 자정을 넘기면 쿼리가 깨진다.
     */
    private LocalTime timeSlotTo;
    private String status;         // "ONGOING" | "ENDED" | null(전체)
    private LocalDate today;       // status 판정 기준일
    private String venue;
    private String region;
    private String keyword;
    /**
     * 정렬 키. {@code PerformanceListService.Sort} 의 코드 문자열이며, 매퍼는 이 값을
     * {@code <choose>} 로만 비교한다 — {@code ${}} 로 ORDER BY 에 그대로 이어붙이면
     * SQL 주입이 된다. 알 수 없는 값은 서비스에서 기본 정렬로 바뀐 뒤 들어온다.
     */
    private String sort;
    private Integer offset;
    private Integer limit;
}
