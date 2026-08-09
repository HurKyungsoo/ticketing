package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 한 공연의 관람평 집계. 상세 요약 카드의 "★ 4.3 · 12명" 한 줄에 쓴다.
 *
 * <p>평균을 화면에서 계산하지 않는 이유: 그러려면 관람평 전체를 읽어와야 하는데,
 * 요약 카드는 개수와 평균만 필요하다. 관람평이 쌓일수록 그 낭비가 커진다.
 */
@Getter
@Setter
public class ReviewStatRow {
    private long count;
    /** 관람평이 없으면 null 이다(AVG 의 결과) — 화면이 그 줄을 통째로 숨긴다. */
    private Double average;
}
