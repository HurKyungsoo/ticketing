package com.portfolio.ticket;

import com.portfolio.ticket.domain.BookingLink;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.KopisDetailBackfill;
import com.portfolio.ticket.external.KopisFetchException;
import com.portfolio.ticket.external.KopisPerformanceClient;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class KopisDetailBackfillTest {

    @Autowired
    KopisDetailBackfill backfill;
    @Autowired
    PerformanceRepository performanceRepository;
    @Autowired
    PerformanceScheduleRepository scheduleRepository;

    @MockBean
    KopisPerformanceClient kopisClient;

    @AfterEach
    void tearDown() {
        performanceRepository.findAll().stream()
                .filter(p -> p.getExternalId().startsWith("KOPIS-BF"))
                .forEach(performanceRepository::delete);
    }

    @Test
    void 상세가_빠진_상영_중_공연만_채우고_회차는_건드리지_않는다() {
        Performance missing = save("KOPIS-BF1", LocalDate.now().plusDays(10), null, null);
        Performance filled = save("KOPIS-BF2", LocalDate.now().plusDays(10), 50_000, "FC-OLD");
        Performance ended = save("KOPIS-BF3", LocalDate.now().minusDays(1), null, null);
        long schedulesBefore = scheduleRepository.count();
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(detailed(i.getArgument(0))));

        int applied = backfill.run();

        assertThat(applied).isEqualTo(1);
        Performance after = performanceRepository.findById(missing.getId()).orElseThrow();
        assertThat(after.getBasePrice()).isEqualTo(30_000);
        assertThat(after.getVenueHallId()).isEqualTo("FC-NEW");
        assertThat(after.getRunningTime()).isEqualTo("50분");
        assertThat(after.getDetailFetchedOn()).isEqualTo(LocalDate.now());
        assertThat(performanceRepository.findById(filled.getId()).orElseThrow().getVenueHallId()).isEqualTo("FC-OLD");
        assertThat(performanceRepository.findById(ended.getId()).orElseThrow().getDetailFetchedOn()).isNull();
        assertThat(scheduleRepository.count()).isEqualTo(schedulesBefore);
    }

    @Test
    void 받아_본_공연은_정보가_없어도_다시_고르지_않는다() {
        save("KOPIS-BF4", LocalDate.now().plusDays(10), null, null);
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));

        backfill.run();
        backfill.run();

        verify(kopisClient, times(1)).fetchDetail(any());
    }

    @Test
    void 응답을_못_받은_공연은_다음에_다시_시도한다() {
        Performance target = save("KOPIS-BF5", LocalDate.now().plusDays(10), null, null);
        when(kopisClient.fetchDetail(any())).thenReturn(Optional.empty());

        assertThat(backfill.run()).isZero();

        assertThat(performanceRepository.findById(target.getId()).orElseThrow().getDetailFetchedOn()).isNull();
    }

    @Test
    void 차단이_풀리지_않으면_남은_공연은_호출하지_않는다() {
        save("KOPIS-BF6", LocalDate.now().plusDays(10), null, null);
        save("KOPIS-BF7", LocalDate.now().plusDays(11), null, null);
        when(kopisClient.fetchDetail(any())).thenThrow(new KopisFetchException("차단"));

        assertThat(backfill.run()).isZero();

        verify(kopisClient, times(1)).fetchDetail(any());
    }

    @Test
    void 상세에_예매처가_없어도_기존_예매처를_지우지_않는다() {
        Performance target = save("KOPIS-BF8", LocalDate.now().plusDays(10), null, null);
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));

        backfill.run();

        assertThat(performanceRepository.findByIdWithBookingLinks(target.getId()).orElseThrow().getBookingLinks())
                .hasSize(1);
        verify(kopisClient, never()).fetchPage(org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private Performance save(String externalId, LocalDate endDate, Integer basePrice, String hallId) {
        return performanceRepository.save(Performance.builder()
                .externalId(externalId)
                .sourceType(SourceType.KOPIS)
                .title("보충 테스트 " + externalId)
                .genre("연극")
                .category(PerformanceCategory.PLAY)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(endDate.minusDays(5))
                .endDate(endDate)
                .basePrice(basePrice)
                .venueHallId(hallId)
                .bookingLinks(new java.util.ArrayList<>(List.of(new BookingLink("예매처", "https://example.com"))))
                .build());
    }

    private static ExternalPerformance detailed(ExternalPerformance basic) {
        return ExternalPerformance.builder()
                .externalId(basic.getExternalId())
                .sourceType(basic.getSourceType())
                .title(basic.getTitle())
                .genre(basic.getGenre())
                .venue(basic.getVenue())
                .region(basic.getRegion())
                .startDate(basic.getStartDate())
                .endDate(basic.getEndDate())
                .venueHallId("FC-NEW")
                .runningTime("50분")
                .basePrice(30_000)
                .bookingLinks(basic.getBookingLinks())
                .build();
    }
}
