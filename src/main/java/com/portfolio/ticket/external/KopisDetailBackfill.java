package com.portfolio.ticket.external;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 수집 때 상세를 놓친 KOPIS 공연을 하루 정해진 양만큼 다시 받아 채운다.
 *
 * <p>증분 수집은 원본이 바뀐 공연만 다시 내려주므로, 한 번 상세를 놓친 공연은 그대로 남는다.
 * 예전에 방화벽 차단으로 상세 호출이 거의 전부 실패해 상영 중 공연의 절반 넘게 가격·공연장이 비었다.
 * 호출 간격과 차단 재시도는 {@link KopisPerformanceClient} 가 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KopisDetailBackfill {

    private final KopisPerformanceClient kopisClient;
    private final KopisDetailBackfillService backfillService;
    private final PublicDataProperties properties;

    /** @return 상세를 받아 반영한 공연 수 */
    public int run() {
        int limit = properties.getKopisDetailBackfillLimit();
        if (limit <= 0) return 0;

        List<ExternalPerformance> targets = backfillService.findTargets(limit);
        int applied = 0;
        for (ExternalPerformance target : targets) {
            try {
                Optional<ExternalPerformance> detailed = kopisClient.fetchDetail(target);
                if (detailed.isEmpty()) continue;
                backfillService.apply(detailed.get());
                applied++;
            } catch (KopisFetchException e) {
                log.warn("[KOPIS 상세 보충] 차단이 풀리지 않아 오늘은 여기서 멈춥니다. 반영 {}건", applied);
                break;
            } catch (Exception e) {
                // 한 건 때문에 나머지를 버리지 않는다 (CLAUDE.md 외부 API 규칙).
                log.warn("[KOPIS 상세 보충] 건너뜀. externalId={}, msg={}", target.getExternalId(), e.getMessage());
            }
        }
        log.info("[KOPIS 상세 보충] 대상 {}건, 반영 {}건", targets.size(), applied);
        return applied;
    }
}
