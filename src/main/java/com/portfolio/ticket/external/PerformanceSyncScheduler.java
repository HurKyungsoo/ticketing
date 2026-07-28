package com.portfolio.ticket.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.IntFunction;

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
    private final KopisPerformanceClient kopisClient;
    private final PerformanceSyncService syncService;
    private final PublicDataProperties properties;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void syncDaily() {
        runSync();
    }

    /** 소스별 동기화 결과. 한쪽이 실패해도 error 에 사유만 담고 나머지 소스는 계속 진행한다. */
    public record SourceSyncResult(int received, int created, int skipped, boolean success, String error) {

        static SourceSyncResult success(int received, int created) {
            return new SourceSyncResult(received, created, received - created, true, null);
        }

        static SourceSyncResult failure(int received, int created, String error) {
            return new SourceSyncResult(received, created, received - created, false, error);
        }
    }

    public record SyncSummary(SourceSyncResult standard, SourceSyncResult culture, SourceSyncResult kopis) {

        public int totalCreated() {
            return standard.created() + culture.created() + kopis.created();
        }
    }

    public SyncSummary runSync() {
        SourceSyncResult standard = syncSource("표준데이터", performanceClient::fetchPage);
        SourceSyncResult culture = syncSource("문화정보", cultureClient::fetchPage);
        SourceSyncResult kopis = syncSource("KOPIS", kopisClient::fetchPage);

        log.info("전체 동기화 완료. 표준데이터 신규 {}건, 문화정보 신규 {}건, KOPIS 신규 {}건",
                standard.created(), culture.created(), kopis.created());
        return new SyncSummary(standard, culture, kopis);
    }

    /**
     * 소스 하나를 완전히 격리해서 실행한다. 여기서 예외가 나도 다른 소스 실행에는
     * 영향을 주지 않아야 한다 (CLAUDE.md 외부 API 규칙).
     */
    private SourceSyncResult syncSource(String sourceName, IntFunction<List<ExternalPerformance>> fetcher) {
        log.info("[{}] 동기화 시작", sourceName);
        int received = 0;
        int created = 0;

        try {
            for (int page = 1; page <= properties.getSyncMaxPages(); page++) {
                List<ExternalPerformance> items = fetcher.apply(page);
                if (items.isEmpty()) break;

                PerformanceSyncService.SyncBatchResult result = syncService.sync(items);
                received += result.received();
                created += result.created();
            }
            log.info("[{}] 동기화 완료. 수신 {}건, 신규 {}건", sourceName, received, created);
            return SourceSyncResult.success(received, created);
        } catch (Exception e) {
            log.error("[{}] 동기화 중 예외 발생. 여기까지 수신 {}건, 신규 {}건 반영됨. msg={}",
                    sourceName, received, created, e.getMessage(), e);
            return SourceSyncResult.failure(received, created, e.getMessage());
        }
    }
}
