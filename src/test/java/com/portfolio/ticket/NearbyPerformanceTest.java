package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.mapper.dto.PerformanceListRow;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.service.PerformanceListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주변 공연 찾기. 브라우저 geolocation 이 준 좌표(반경 5km) 안의 공연을 가까운 순으로
 * 보여준다 — KOPIS 가이드의 "주변정보찾기"(반경 1~5km)를 참고했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NearbyPerformanceTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceListService listService;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;

    // 서울 시청 부근을 원점으로 잡는다. 위도 1도 ≈ 111km 라 오프셋으로 대략의 거리를 안다.
    private static final double ORIGIN_LAT = 37.5665;
    private static final double ORIGIN_LNG = 126.9780;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    private Performance performanceAt(String title, Double lat, Double lng, LocalDate endDate) {
        return performanceRepository.save(Performance.builder()
                .externalId("NEARBY-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .latitude(lat)
                .longitude(lng)
                .startDate(LocalDate.now().minusDays(1))
                .endDate(endDate)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    @Test
    @DisplayName("반경 안은 가까운 순, 반경 밖·좌표 없음·종료작은 빠진다")
    void nearbyFiltersAndOrdersByDistance() {
        // 원점에서 거의 0km.
        Performance near = performanceAt("가까운공연", ORIGIN_LAT, ORIGIN_LNG, LocalDate.now().plusDays(30));
        // 위도 0.03도 ≈ 3.3km — 반경(5km) 안이지만 near 보다 멀다.
        Performance mid = performanceAt("중간공연", ORIGIN_LAT + 0.03, ORIGIN_LNG, LocalDate.now().plusDays(30));
        // 위도 0.5도 ≈ 55km — 반경 밖.
        performanceAt("먼공연", ORIGIN_LAT + 0.5, ORIGIN_LNG, LocalDate.now().plusDays(30));
        // 좌표가 없는 공연(표준데이터/문화정보 일부와 같은 상황) — 거리를 잴 수 없어 제외.
        performanceAt("좌표없는공연", null, null, LocalDate.now().plusDays(30));
        // 반경 안이지만 이미 끝난 공연 — 예매할 수 없으므로 제외.
        performanceAt("종료된공연", ORIGIN_LAT, ORIGIN_LNG, LocalDate.now().minusDays(1));

        PerformanceListService.NearbyResult result = listService.nearby(ORIGIN_LAT, ORIGIN_LNG);

        assertThat(result.radiusKm()).isEqualTo(5.0);
        assertThat(result.performances()).extracting(PerformanceListRow::getTitle)
                .containsExactly("가까운공연", "중간공연");
        assertThat(result.performances().get(0).getDistanceKm())
                .isLessThan(result.performances().get(1).getDistanceKm());
        // 근접 원점은 부동소수점 반올림으로 거리가 미세 음수/NaN 이 되지 않아야 한다
        // (LEAST/GREATEST 로 ACOS 인자를 [-1,1] 로 죈 이유 — PerformanceMapper.xml 참고).
        assertThat(result.performances().get(0).getDistanceKm()).isNotNaN().isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("5km 안에 없으면 20km 로 넓혀서 찾고, 실제로 쓴 반경을 같이 돌려준다")
    void nearbyFallsBackToWiderRadiusWhenEmpty() {
        // 위도 0.1도 ≈ 11km — 5km 밖, 20km 안.
        performanceAt("조금먼공연", ORIGIN_LAT + 0.1, ORIGIN_LNG, LocalDate.now().plusDays(30));

        PerformanceListService.NearbyResult result = listService.nearby(ORIGIN_LAT, ORIGIN_LNG);

        assertThat(result.radiusKm()).isEqualTo(20.0);
        assertThat(result.performances()).extracting(PerformanceListRow::getTitle)
                .containsExactly("조금먼공연");
    }

    @Test
    @DisplayName("가장 넓은 반경(20km)에도 없으면 그 반경 기준의 빈 결과를 돌려준다")
    void nearbyReturnsWidestRadiusWhenStillEmpty() {
        // 위도 0.5도 ≈ 55km — 20km 밖.
        performanceAt("아주먼공연", ORIGIN_LAT + 0.5, ORIGIN_LNG, LocalDate.now().plusDays(30));

        PerformanceListService.NearbyResult result = listService.nearby(ORIGIN_LAT, ORIGIN_LNG);

        assertThat(result.radiusKm()).isEqualTo(20.0);
        assertThat(result.performances()).isEmpty();
    }

    @Test
    @DisplayName("distanceLabel: 1km 미만은 미터, 이상은 소수 첫째자리 km")
    void distanceLabelFormatting() {
        PerformanceListRow row = new PerformanceListRow();

        row.setDistanceKm(0.65);
        assertThat(row.distanceLabel()).isEqualTo("650m");

        row.setDistanceKm(1.24);
        assertThat(row.distanceLabel()).isEqualTo("1.2km");

        row.setDistanceKm(null);
        assertThat(row.distanceLabel()).isNull();
    }

    @Test
    @DisplayName("좌표 파라미터가 없으면 위치 허용 안내만 뜨고 카드 그리드는 없다")
    void withoutLocationShowsPrompt() throws Exception {
        mockMvc.perform(get("/performances/nearby"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("내 위치로 찾기")))
                .andExpect(content().string(not(containsString("반경"))));
    }

    @Test
    @DisplayName("유효한 좌표면 그 위치 기준 결과가 뜬다")
    void withLocationShowsResults() throws Exception {
        performanceAt("근처공연", ORIGIN_LAT, ORIGIN_LNG, LocalDate.now().plusDays(30));

        mockMvc.perform(get("/performances/nearby").param("lat", String.valueOf(ORIGIN_LAT))
                        .param("lng", String.valueOf(ORIGIN_LNG)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("근처공연")))
                // 반경 숫자가 별도 <span> 이라 "반경 5km 이내" 는 더 이상 이어진 문자열이 아니다
                // (PerformanceListService.NearbyResult 가 실제 반경을 돌려주면서 바뀌었다).
                .andExpect(content().string(containsString(">5</span>km 이내")));
    }

    @Test
    @DisplayName("범위를 벗어난 좌표(위도 90 초과)는 손으로 고친 값으로 보고 안내 화면으로 되돌린다")
    void outOfRangeLatitudeFallsBackToPrompt() throws Exception {
        mockMvc.perform(get("/performances/nearby").param("lat", "9999").param("lng", "127"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("내 위치로 찾기")));
    }
}
