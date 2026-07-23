package com.portfolio.ticket.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 매일 새벽 4시 공연 데이터 수집 배치.
 *
 * 개발계정 트래픽 제한(보통 일 1,000건) 때문에 실시간 호출이 아니라
 * 배치로 긁어와 로컬 DB 에 적재하는 구조를 택했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceSyncScheduler {

    private final PublicPerformanceClient performanceClient;
    private final CulturePerformanceClient cultureClient;
    private final PerformanceSyncService syncService;
    private final PublicDataProperties properties;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void syncDaily() {
        runSync();
    }

    public int runSync() {
        int total = 0;

        for (int page = 1; page <= properties.getSyncMaxPages(); page++) {
            List<ExternalPerformance> standard = performanceClient.fetchPage(page);
            if (standard.isEmpty()) break;
            total += syncService.sync(standard);
        }

        for (int page = 1; page <= properties.getSyncMaxPages(); page++) {
            List<ExternalPerformance> culture = cultureClient.fetchPage(page);
            if (culture.isEmpty()) break;
            total += syncService.sync(culture);
        }

        log.info("전체 동기화 완료. 신규 {}건", total);
        return total;
    }
}
