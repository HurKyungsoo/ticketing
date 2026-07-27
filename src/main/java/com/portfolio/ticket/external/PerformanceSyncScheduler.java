package com.portfolio.ticket.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 매일 새벽 4시 공연 데이터 수집 배치.
 *
 * 개발계정 트래픽 제한(보통 일 1,000건) 때문에 실시간 호출이 아니라
 * 배치로 긁어와 로컬 DB 에 적재하는 구조를 택했다.
 *
 * 소스를 3개(전국공연행사정보표준데이터 / 한눈에보는문화정보 / 서울 열린데이터광장
 * 문화행사정보) 로 이중화했다. 서로 다른 기관 API 라 한쪽 인증키 승인이 막혀도
 * 나머지 소스로 화면 확인이 가능하다. 소스 하나가 죽어도(호출 실패든 DB 저장
 * 실패든) 다른 소스는 계속 돌도록 소스별로 예외를 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceSyncScheduler {

    private final PublicPerformanceClient performanceClient;
    private final CulturePerformanceClient cultureClient;
    private final SeoulCultureClient seoulCultureClient;
    private final PerformanceSyncService syncService;
    private final PublicDataProperties properties;
    private final SeoulOpenApiProperties seoulProperties;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void syncDaily() {
        runSync();
    }

    public SyncResult runSync() {
        List<SourceSyncResult> sources = new ArrayList<>();
        sources.add(syncSource("전국공연행사정보표준데이터", properties.getSyncMaxPages(), performanceClient::fetchPage));
        sources.add(syncSource("한눈에보는문화정보", properties.getSyncMaxPages(), cultureClient::fetchPage));
        sources.add(syncSource("서울 열린데이터광장 문화행사정보", seoulProperties.getSyncMaxPages(), seoulCultureClient::fetchPage));

        int total = sources.stream().mapToInt(SourceSyncResult::newlySaved).sum();
        log.info("전체 동기화 완료. 신규 {}건", total);
        return new SyncResult(total, sources);
    }

    /**
     * 소스 하나를 끝까지 돌린다. 호출 실패든 DB 저장 실패든 이 소스 안에서만
     * 예외를 삼켜서 다른 소스의 동기화를 막지 않는다.
     */
    private SourceSyncResult syncSource(String sourceName, int maxPages, IntFunction<FetchResult> fetcher) {
        int attempted = 0;
        int received = 0;
        int parsed = 0;
        int saved = 0;

        try {
            for (int page = 1; page <= maxPages; page++) {
                attempted++;
                FetchResult result = fetcher.apply(page);
                if (result.isEmpty()) break;

                received += result.getReceived();
                parsed += result.getParsed().size();
                saved += syncService.sync(result.getParsed());
            }
        } catch (Exception e) {
            log.warn("{} 동기화 중 오류가 나서 중단합니다. 다른 소스는 계속 진행합니다. msg={}",
                    sourceName, e.getMessage());
        }

        int invalid = received - parsed;
        log.info("{} 동기화 결과. 시도={}, 수신={}, 파싱성공={}, isValid탈락={}, 신규저장={}",
                sourceName, attempted, received, parsed, invalid, saved);
        return new SourceSyncResult(sourceName, attempted, received, parsed, invalid, saved);
    }
}
