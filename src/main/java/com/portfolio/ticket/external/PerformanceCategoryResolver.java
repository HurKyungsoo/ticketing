package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.PerformanceCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 소스마다 genre 원본 문자열이 다르다 (KOPIS "서양음악(클래식)", 표준데이터 "공연클래식" 등).
 * 정확히 일치하는 값만 찾으면 "공연연극"/"공연클래식" 같은 변형을 다 놓치므로
 * 키워드 포함(contains) 방식으로 매핑한다. LinkedHashMap 순서가 매칭 우선순위다
 * (예: "무용" 이 "대중무용" 도 잡아야 하니 순서 신경쓸 필요는 없지만, 겹치는 키워드가
 * 생기면 위에 있는 항목이 먼저 매칭된다).
 */
@Slf4j
@Component
public class PerformanceCategoryResolver {

    private static final Map<String, PerformanceCategory> KEYWORD_TO_CATEGORY = new LinkedHashMap<>();

    static {
        KEYWORD_TO_CATEGORY.put("뮤지컬", PerformanceCategory.MUSICAL);
        KEYWORD_TO_CATEGORY.put("연극", PerformanceCategory.PLAY);
        KEYWORD_TO_CATEGORY.put("대중음악", PerformanceCategory.CONCERT);
        KEYWORD_TO_CATEGORY.put("클래식", PerformanceCategory.CLASSIC);
        KEYWORD_TO_CATEGORY.put("국악", PerformanceCategory.TRADITIONAL);
        KEYWORD_TO_CATEGORY.put("무용", PerformanceCategory.DANCE);
        KEYWORD_TO_CATEGORY.put("서커스", PerformanceCategory.CIRCUS_MAGIC);
        KEYWORD_TO_CATEGORY.put("마술", PerformanceCategory.CIRCUS_MAGIC);
        KEYWORD_TO_CATEGORY.put("복합", PerformanceCategory.COMPLEX);
    }

    /** 못 찾으면 ETC 로 떨어뜨리되, 규칙을 계속 다듬을 수 있게 원본 값을 WARN 로 남긴다. */
    public PerformanceCategory resolve(String rawGenre) {
        if (rawGenre == null || rawGenre.isBlank()) {
            return PerformanceCategory.ETC;
        }

        for (Map.Entry<String, PerformanceCategory> entry : KEYWORD_TO_CATEGORY.entrySet()) {
            if (rawGenre.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        log.warn("카테고리 매핑 실패, ETC 로 분류. rawGenre={}", rawGenre);
        return PerformanceCategory.ETC;
    }
}
