package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 카테고리/월/공연장 필터 옵션별 건수. label 은 쿼리마다 다른 값(카테고리명/월/공연장명)이
 * 들어간다. "key" 로 이름 지었다가 H2/MySQL 예약어라 별칭 파싱 에러가 나서 label 로 바꿨다.
 */
@Getter
@Setter
public class FacetCountRow {
    private String label;
    private long count;
}
