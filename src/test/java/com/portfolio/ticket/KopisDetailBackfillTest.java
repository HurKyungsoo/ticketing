package com.portfolio.ticket;

import com.portfolio.ticket.domain.BookingLink;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.KopisDetailBackfill;
import com.portfolio.ticket.external.KopisFetchException;
import com.portfolio.ticket.external.KopisPerformanceClient;
import com.portfolio.ticket.external.PublicDataProperties;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
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

    private static final String SHOW_TIMES = "토요일(15:00,19:00)";
    private static final String PRICES = "R석 60,000원, S석 40,000원";

    @Autowired
    KopisDetailBackfill backfill;
    @Autowired
    PerformanceRepository performanceRepository;
    @Autowired
    PerformanceScheduleRepository scheduleRepository;
    @Autowired
    PublicDataProperties properties;

    @MockBean
    KopisPerformanceClient kopisClient;

    /** 다른 테스트가 남긴 공연이 보충 대상으로 섞이지 않게, 이미 받아 본 것으로 표시해 둔다. */
    @BeforeEach
    void isolateFromOtherTests() {
        List<Performance> others = performanceRepository.findAll();
        others.forEach(p -> p.markDetailFetched(LocalDate.now().minusDays(1)));
        performanceRepository.saveAll(others);
    }

    @AfterEach
    void tearDown() {
        properties.setKopisDetailBackfillLimit(300);
        performanceRepository.findAll().stream()
                .filter(p -> p.getExternalId().startsWith("KOPIS-BF"))
                .forEach(performanceRepository::delete);
    }

    @Test
    void 상영_중이고_원문이_없는_공연만_채우고_회차는_건드리지_않는다() {
        Performance missing = save("KOPIS-BF1", LocalDate.now().plusDays(10), null, null, null);
        Performance hasGuidance = save("KOPIS-BF2", LocalDate.now().plusDays(10), 50_000, "FC-OLD", SHOW_TIMES);
        Performance ended = save("KOPIS-BF3", LocalDate.now().minusDays(1), null, null, null);
        long schedulesBefore = scheduleRepository.count();
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(detailed(i.getArgument(0))));

        int applied = backfill.run();

        assertThat(applied).isEqualTo(1);
        Performance after = performanceRepository.findById(missing.getId()).orElseThrow();
        assertThat(after.getBasePrice()).isEqualTo(30_000);
        assertThat(after.getVenueHallId()).isEqualTo("FC-NEW");
        assertThat(after.getRunningTime()).isEqualTo("50분");
        assertThat(after.getShowTimeGuidance()).isEqualTo(SHOW_TIMES);
        assertThat(after.getPriceGuidance()).isEqualTo(PRICES);
        assertThat(after.getDetailFetchedOn()).isEqualTo(LocalDate.now());
        assertThat(performanceRepository.findById(hasGuidance.getId()).orElseThrow().getVenueHallId()).isEqualTo("FC-OLD");
        assertThat(performanceRepository.findById(ended.getId()).orElseThrow().getDetailFetchedOn()).isNull();
        assertThat(scheduleRepository.count()).isEqualTo(schedulesBefore);
    }

    @Test
    void 가격이_있어도_원문이_없으면_대상이지만_정보가_빠진_공연을_먼저_받는다() {
        save("KOPIS-BF10", LocalDate.now().plusDays(3), 50_000, "FC-OLD", null);
        save("KOPIS-BF11", LocalDate.now().plusDays(30), null, null, null);
        properties.setKopisDetailBackfillLimit(1);
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));

        backfill.run();

        ArgumentCaptor<ExternalPerformance> called = ArgumentCaptor.forClass(ExternalPerformance.class);
        verify(kopisClient).fetchDetail(called.capture());
        assertThat(called.getValue().getExternalId()).isEqualTo("KOPIS-BF11");
    }

    @Test
    void 받아_본_공연은_정보가_없어도_다시_고르지_않는다() {
        save("KOPIS-BF4", LocalDate.now().plusDays(10), null, null, null);
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));

        backfill.run();
        backfill.run();

        verify(kopisClient, times(1)).fetchDetail(any());
    }

    @Test
    void 응답을_못_받은_공연은_다음에_다시_시도한다() {
        Performance target = save("KOPIS-BF5", LocalDate.now().plusDays(10), null, null, null);
        when(kopisClient.fetchDetail(any())).thenReturn(Optional.empty());

        assertThat(backfill.run()).isZero();

        assertThat(performanceRepository.findById(target.getId()).orElseThrow().getDetailFetchedOn()).isNull();
    }

    @Test
    void 차단이_풀리지_않으면_남은_공연은_호출하지_않는다() {
        save("KOPIS-BF6", LocalDate.now().plusDays(10), null, null, null);
        save("KOPIS-BF7", LocalDate.now().plusDays(11), null, null, null);
        when(kopisClient.fetchDetail(any())).thenThrow(new KopisFetchException("차단"));

        assertThat(backfill.run()).isZero();

        verify(kopisClient, times(1)).fetchDetail(any());
    }

    @Test
    void 상세에_예매처가_없어도_기존_예매처를_지우지_않는다() {
        Performance target = save("KOPIS-BF8", LocalDate.now().plusDays(10), null, null, null);
        when(kopisClient.fetchDetail(any())).thenAnswer(i -> Optional.of(i.getArgument(0)));

        backfill.run();

        assertThat(performanceRepository.findByIdWithBookingLinks(target.getId()).orElseThrow().getBookingLinks())
                .hasSize(1);
        verify(kopisClient, never()).fetchPage(org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private Performance save(String externalId, LocalDate endDate, Integer basePrice, String hallId,
                             String showTimeGuidance) {
        Performance performance = Performance.builder()
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
                .bookingLinks(new ArrayList<>(List.of(new BookingLink("예매처", "https://example.com"))))
                .build();
        performance.updateGuidance(showTimeGuidance, null);
        return performanceRepository.save(performance);
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
                .showTimeGuidance(SHOW_TIMES)
                .priceGuidance(PRICES)
                .bookingLinks(basic.getBookingLinks())
                .build();
    }
}
