package com.portfolio.ticket.external;

/**
 * 소스 하나의 수집 결과. {@code created} 만 보고는 "호출 자체가 실패한 건지"
 * "중복이라 신규 0건인 건지" 구분이 안 돼서 단계별로 나눴다.
 */
public record SourceSyncResult(
        String source,
        int attemptedPages,
        int received,
        int parsedSuccess,
        int invalidSkipped,
        int newlySaved
) {
}
