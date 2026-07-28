package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 공연 목록 필터 조건. 값이 null(또는 빈 문자열)이면 "전체"(필터 없음)를 뜻한다.
 * 쿼리 파라미터의 "all" 센티넬 -> null 변환은 컨트롤러 책임이고, 매퍼는 null 만 안다.
 */
@Getter
@Setter
public class PerformanceFilter {
    private String category;       // PerformanceCategory.name()
    private Integer month;         // 1~12
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
    private Integer offset;
    private Integer limit;
}
