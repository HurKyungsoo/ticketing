package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.domain.SyncState;
import com.portfolio.ticket.repository.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
    private final SyncStateRepository syncStateRepository;

    /**
     * 증분 수집 시 마지막 성공일보다 하루 더 앞에서부터 다시 받는다.
     * afterdate 가 일 단위라, 배치가 도는 도중 등록된 건이 경계에서 새는 것을 막는 안전마진이다.
     * 겹친 만큼은 이미 있는 건이라 갱신만 되고 새로 만들어지지 않는다.
     */
    private static final int OVERLAP_DAYS = 1;

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

    public record SyncSummary(SourceSyncResult standard, SourceSyncResult culture, SourceSyncResult kopis,
                              LocalDate kopisAfterDate, int purged, int staleSchedulesToppedUp) {

        public int totalCreated() {
            return standard.created() + culture.created() + kopis.created();
        }

        /** KOPIS 를 증분으로 돌렸는지. 표준데이터/문화정보는 afterdate 에 해당하는 파라미터가 없어 항상 전체다. */
        public boolean kopisIncremental() {
            return kopisAfterDate != null;
        }
    }

    public SyncSummary runSync() {
        return runSync(false);
    }

    /** @param full true 면 마지막 수집 이력을 무시하고 전체를 다시 받는다. */
    public SyncSummary runSync(boolean full) {
        SourceSyncResult standard = syncSource("표준데이터", performanceClient::fetchPage);
        SourceSyncResult culture = syncSource("문화정보", cultureClient::fetchPage);

        LocalDate afterDate = full ? null : resolveKopisAfterDate();
        LocalDate startedOn = LocalDate.now();
        SourceSyncResult kopis = syncSource(
                afterDate == null ? "KOPIS(전체)" : "KOPIS(증분 " + afterDate + " 이후)",
                page -> kopisClient.fetchPage(page, afterDate));

        // 실패한 회차의 날짜를 기준점으로 남기면 그 구간이 영영 수집되지 않는다. 성공했을 때만 갱신한다.
        if (kopis.success()) {
            markKopisSynced(startedOn);
        }

        // 수집 루프의 skip 은 "다시 내려온" 항목만 막는다. 규칙이 생기기 전에 들어와 이미
        // DB 에 있는 건은 여기서 걷어낸다 (멱등이라 매번 돌려도 안전하다).
        int purged = syncService.purgeNonPerformances();

        // 외부 응답과 무관한 안전망. 증분 수집은 원본이 안 바뀐 공연을 다시 내려주지 않으므로
        // 위 세 소스 동기화만으로는 오래 안 건드려진 공연의 회차가 계속 빌 수 있다.
        int staleToppedUp = syncService.topUpStaleSchedules();

        log.info("전체 동기화 완료. 표준데이터 신규 {}건, 문화정보 신규 {}건, KOPIS 신규 {}건, 제외 {}건, 회차 보충 {}건",
                standard.created(), culture.created(), kopis.created(), purged, staleToppedUp);
        return new SyncSummary(standard, culture, kopis, afterDate, purged, staleToppedUp);
    }

    /** 이력이 없으면(최초 수집, DB 초기화 직후) null 을 반환해 전체 수집으로 떨어뜨린다. */
    private LocalDate resolveKopisAfterDate() {
        return syncStateRepository.findById(SourceType.KOPIS)
                .map(state -> state.getLastSyncedOn().minusDays(OVERLAP_DAYS))
                .orElse(null);
    }

    private void markKopisSynced(LocalDate syncedOn) {
        SyncState state = syncStateRepository.findById(SourceType.KOPIS).orElse(null);
        if (state == null) {
            syncStateRepository.save(SyncState.builder()
                    .sourceType(SourceType.KOPIS)
                    .lastSyncedOn(syncedOn)
                    .build());
            return;
        }
        state.markSyncedOn(syncedOn);
        syncStateRepository.save(state);
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
