package com.portfolio.ticket.domain;

/** 필터/카테고리 탭에 쓰는 정규화된 장르. 소스별 원본 genre 문자열은 PerformanceCategoryResolver 가 여기로 매핑한다. */
public enum PerformanceCategory {
    MUSICAL("뮤지컬"),
    PLAY("연극"),
    CONCERT("대중음악"),
    CLASSIC("클래식"),
    TRADITIONAL("국악"),
    DANCE("무용"),
    CIRCUS_MAGIC("서커스/마술"),
    COMPLEX("복합"),
    ETC("기타");

    private final String label;

    PerformanceCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
