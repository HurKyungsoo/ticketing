package com.portfolio.ticket.external;

import lombok.Getter;

import java.util.List;

/**
 * 한 페이지 호출 결과. 원본 수신 건수와 파싱에 성공한 건수를 분리해 둬야
 * 소스별 수집 통계(시도/수신/파싱성공/신규저장)를 로그로 남길 수 있다.
 */
@Getter
public class FetchResult {

    private final int received;
    private final List<ExternalPerformance> parsed;

    public FetchResult(int received, List<ExternalPerformance> parsed) {
        this.received = received;
        this.parsed = parsed;
    }

    public static FetchResult empty() {
        return new FetchResult(0, List.of());
    }

    public boolean isEmpty() {
        return received == 0;
    }
}
