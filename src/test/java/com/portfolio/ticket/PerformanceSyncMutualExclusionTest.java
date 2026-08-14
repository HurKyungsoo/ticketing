package com.portfolio.ticket;

import com.portfolio.ticket.external.CulturePerformanceClient;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.KopisPerformanceClient;
import com.portfolio.ticket.external.PerformanceSyncScheduler;
import com.portfolio.ticket.external.PublicPerformanceClient;
import com.portfolio.ticket.external.SyncAlreadyRunningException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 수동 트리거(POST /api/admin/sync)와 새벽 4시 cron 이 겹치면 같은 PERFORMANCE_SCHEDULE
 * 행을 두 트랜잭션이 동시에 써서 Concurrent update → 롤백 실패 → 커넥션 파손까지 갔던
 * 사고가 있었다(README 트러블슈팅). 재발을 막는 상호배제 검증.
 *
 * <p>세 소스 클라이언트를 전부 목으로 막아둔다 — 로컬 셸에 PUBLICDATA_SERVICE_KEY 가
 * 실제로 설정돼 있으면(개발 편의상 흔하다) 목 없이는 이 테스트가 실제 공공데이터 API 를
 * 그대로 호출해 수 분씩 걸리고 네트워크 상태에 흔들린다 — 처음 이 목을 하나만(표준데이터)
 * 걸었다가 실제로 겪었다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PerformanceSyncMutualExclusionTest {

    @Autowired
    PerformanceSyncScheduler syncScheduler;

    @MockBean
    PublicPerformanceClient performanceClient;
    @MockBean
    CulturePerformanceClient cultureClient;
    @MockBean
    KopisPerformanceClient kopisClient;

    @BeforeEach
    void setUp() {
        when(cultureClient.fetchPage(anyInt())).thenReturn(List.of());
        when(kopisClient.fetchPage(anyInt(), any())).thenReturn(List.of());
    }

    @DisplayName("동기화가 진행 중일 때 겹쳐 들어온 호출은 시작하지 않고 예외를 던진다")
    @Test
    void secondCallDuringSyncIsRejected() {
        AtomicReference<Exception> caughtDuringFirstCall = new AtomicReference<>();

        // 첫 호출이 소스를 훑는 도중(락을 쥔 상태) 겹쳐 들어온 두 번째 호출을 흉내낸다.
        when(performanceClient.fetchPage(anyInt())).thenAnswer(invocation -> {
            try {
                syncScheduler.runSync(false);
            } catch (Exception e) {
                caughtDuringFirstCall.set(e);
            }
            return List.<ExternalPerformance>of();
        });

        syncScheduler.runSync(false);

        assertThat(caughtDuringFirstCall.get()).isInstanceOf(SyncAlreadyRunningException.class);
    }

    @DisplayName("동기화가 끝나면 락이 풀려 다음 호출은 정상 진행된다")
    @Test
    void lockIsReleasedAfterCompletion() {
        when(performanceClient.fetchPage(anyInt())).thenReturn(List.of());

        syncScheduler.runSync(false);
        syncScheduler.runSync(false); // 예외 없이 끝나야 락이 풀렸다는 증거다
    }
}
