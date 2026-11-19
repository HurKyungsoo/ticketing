package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 합쳐서 지운 공연의 옛 주소는 남긴 공연으로 보낸다(2026-11-18 전체 점검 발견 4).
 * 전에는 최근 본 공연·공유 링크로 들어오면, 공연은 멀쩡히 있는데 404 만 떴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergedPerformanceRedirectTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceSyncService syncService;
    @Autowired PerformanceRedirectRepository redirectRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        redirectRepository.deleteAll();
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    private Performance save(SourceType source, String title) {
        return performanceRepository.save(Performance.builder()
                .externalId(source.name() + "-" + System.nanoTime())
                .sourceType(source)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("세종문화회관 대극장")
                .region("서울특별시")
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().plusDays(20))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    @DisplayName("합쳐진 공연의 옛 주소는 남긴 공연으로 영구 이동(301)한다")
    @Test
    void mergedAddressMovesToKeeper() throws Exception {
        Performance kopis = save(SourceType.KOPIS, "광화문연가 [서울]");
        Performance culture = save(SourceType.CULTURE, "[서울] 광화문연가");

        assertThat(syncService.mergeCrossSourceDuplicates()).isEqualTo(1);

        mockMvc.perform(get("/performances/{id}", culture.getId()))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/performances/" + kopis.getId()));
        mockMvc.perform(get("/performances/{id}", kopis.getId()))
                .andExpect(status().isOk());
    }

    @DisplayName("남긴 공연이 다시 합쳐지면 옛 기록도 새로 남긴 공연을 가리킨다 — 한 번에 간다")
    @Test
    void chainedMergeRepoints() throws Exception {
        Performance kopis = save(SourceType.KOPIS, "광화문연가 [서울]");
        Performance culture = save(SourceType.CULTURE, "[서울] 광화문연가");
        long older = culture.getId() + 1000;   // 그 전에 culture 에 합쳐졌던 번호
        redirectRepository.save(PerformanceRedirect.builder()
                .fromId(older).toId(culture.getId()).createdAt(LocalDateTime.now()).build());

        syncService.mergeCrossSourceDuplicates();

        assertThat(redirectRepository.findById(older).orElseThrow().getToId()).isEqualTo(kopis.getId());
        mockMvc.perform(get("/performances/{id}", older))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/performances/" + kopis.getId()));
    }

    @DisplayName("남긴 공연까지 없어졌거나 기록이 없는 번호는 그대로 404")
    @Test
    void goneTargetIsNotFound() throws Exception {
        redirectRepository.save(PerformanceRedirect.builder()
                .fromId(900_001L).toId(900_002L).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/performances/{id}", 900_001L)).andExpect(status().isNotFound());
        mockMvc.perform(get("/performances/{id}", 900_003L)).andExpect(status().isNotFound());
    }

    @DisplayName("모집 공고처럼 남길 공연 없이 지운 것은 이동 기록을 남기지 않는다")
    @Test
    void purgeLeavesNoRedirect() {
        save(SourceType.CULTURE, "합창단원 모집");

        syncService.purgeNonPerformances();

        assertThat(redirectRepository.count()).isZero();
    }
}
