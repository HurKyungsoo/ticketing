package com.portfolio.ticket;

import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.domain.SyncState;
import com.portfolio.ticket.external.CulturePerformanceClient;
import com.portfolio.ticket.external.KopisFetchException;
import com.portfolio.ticket.external.KopisPerformanceClient;
import com.portfolio.ticket.external.PerformanceCategoryResolver;
import com.portfolio.ticket.external.PerformanceSyncScheduler;
import com.portfolio.ticket.external.PublicPerformanceClient;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.repository.SyncStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KopisSyncFailureTest {

    private static final LocalDate LAST_SYNCED = LocalDate.of(2026, 1, 1);

    @Autowired
    PerformanceSyncScheduler syncScheduler;
    @Autowired
    SyncStateRepository syncStateRepository;
    @Autowired
    PerformanceCategoryResolver categoryResolver;

    @MockBean
    PublicPerformanceClient performanceClient;
    @MockBean
    CulturePerformanceClient cultureClient;
    @MockBean
    KopisPerformanceClient kopisClient;

    @BeforeEach
    void setUp() {
        when(performanceClient.fetchPage(anyInt())).thenReturn(List.of());
        when(cultureClient.fetchPage(anyInt())).thenReturn(List.of());
        syncStateRepository.save(SyncState.builder()
                .sourceType(SourceType.KOPIS)
                .lastSyncedOn(LAST_SYNCED)
                .build());
    }

    @AfterEach
    void tearDown() {
        syncStateRepository.deleteById(SourceType.KOPIS);
    }

    @Test
    void KOPIS_수집이_실패하면_기준일을_옮기지_않는다() {
        when(kopisClient.fetchPage(anyInt(), any())).thenThrow(new KopisFetchException("차단"));

        PerformanceSyncScheduler.SyncSummary summary = syncScheduler.runSync(false);

        assertThat(summary.kopis().success()).isFalse();
        assertThat(lastSyncedOn()).isEqualTo(LAST_SYNCED);
    }

    @Test
    void 받을_게_없어서_빈_목록이면_기준일을_옮긴다() {
        when(kopisClient.fetchPage(anyInt(), any())).thenReturn(List.of());

        PerformanceSyncScheduler.SyncSummary summary = syncScheduler.runSync(false);

        assertThat(summary.kopis().success()).isTrue();
        assertThat(lastSyncedOn()).isEqualTo(LocalDate.now());
    }

    @Test
    void 장르_문자열을_카테고리로_분류한다() {
        assertThat(categoryResolver.resolve("음악/콘서트")).isEqualTo(PerformanceCategory.CONCERT);
        assertThat(categoryResolver.resolve("서양음악(클래식)")).isEqualTo(PerformanceCategory.CLASSIC);
        assertThat(categoryResolver.resolve("한국음악(국악)")).isEqualTo(PerformanceCategory.TRADITIONAL);
        assertThat(categoryResolver.resolve("대중음악")).isEqualTo(PerformanceCategory.CONCERT);
        assertThat(categoryResolver.resolve("전시")).isEqualTo(PerformanceCategory.ETC);
    }

    private LocalDate lastSyncedOn() {
        return syncStateRepository.findById(SourceType.KOPIS).orElseThrow().getLastSyncedOn();
    }
}
