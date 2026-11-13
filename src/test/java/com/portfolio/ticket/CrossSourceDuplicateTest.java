package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다른 소스로 따로 들어온 같은 공연 합치기.
 *
 * <p>실데이터에서 KOPIS {@code 다이브 [대학로]} / 문화정보 {@code [대학로] 다이브} 가 목록에 연달아
 * 두 번 떴다. "전국 공연을 한곳에" 가 이 사이트의 핵심이라 중복은 첫눈에 보이는 결함이다.
 *
 * <p>NonPerformanceFilterTest 와 마찬가지로 <b>오탐 쪽이 핵심이다</b> — 다른 공연을 합쳐 버리면
 * 그 공연이 조용히 사라진다. 실데이터에 있던 "같은 제목, 다른 상품" 사례를 그대로 넣어 둘 다
 * 살아남는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CrossSourceDuplicateTest {

    @Autowired PerformanceSyncService syncService;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    static final LocalDate START = LocalDate.now().minusDays(10);
    static final LocalDate END = LocalDate.now().plusDays(20);

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

    private Performance save(SourceType source, String title, String venue, String address) {
        return performanceRepository.save(Performance.builder()
                .externalId(source.name() + "-" + title + "-" + System.nanoTime())
                .sourceType(source)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue(venue)
                .address(address)
                .region("서울특별시")
                .startDate(START)
                .endDate(END)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    @DisplayName("KOPIS·문화정보의 같은 공연은 KOPIS 하나만 남고, 비어 있던 주소를 옮겨 받는다")
    @Test
    void mergesSameShowAcrossSources() {
        Performance kopis = save(SourceType.KOPIS, "다이브 [대학로]", "링크더스페이스(LINK THE SPACE)", "-");
        save(SourceType.CULTURE, "[대학로] 다이브", "링크더스페이스(LINK THE SPACE) 1관", "서울 종로구");

        int merged = syncService.mergeCrossSourceDuplicates();

        assertThat(merged).isEqualTo(1);
        List<Performance> left = performanceRepository.findAll();
        assertThat(left).hasSize(1);
        assertThat(left.get(0).getId()).isEqualTo(kopis.getId());
        assertThat(left.get(0).getAddress()).isEqualTo("서울 종로구");
        assertThat(syncService.mergeCrossSourceDuplicates()).isZero();   // 멱등
    }

    @DisplayName("지우는 쪽에 걸린 찜은 남는 쪽으로 옮겨진다")
    @Test
    void movesWishlists() {
        Performance kopis = save(SourceType.KOPIS, "광화문연가 [서울]", "세종문화회관 대극장", "서울 종로구");
        Performance culture = save(SourceType.CULTURE, "[서울] 광화문연가", "세종문화회관 대극장", "서울 종로구");
        wishlistRepository.save(Wishlist.builder().memberId(7L).performance(culture).createdAt(LocalDateTime.now()).build());

        syncService.mergeCrossSourceDuplicates();

        assertThat(wishlistRepository.existsByMemberIdAndPerformanceId(7L, kopis.getId())).isTrue();
    }

    /** 오탐 확인 — 아래는 전부 합치면 안 되는 쌍이다. */
    @DisplayName("같은 제목이라도 다른 상품·다른 공연장·같은 소스·안 겹치는 기간은 합치지 않는다")
    @Test
    void keepsDifferentShows() {
        // 실데이터: 같은 공연의 다른 패키지 상품 (괄호 내용이 다르다)
        save(SourceType.KOPIS, "제1회 ST-NOVA [일반 패키지]", "서울연극창작센터", "서울");
        save(SourceType.CULTURE, "제1회 ST-NOVA [서울연극협회원 패키지]", "서울연극창작센터", "서울");
        // 같은 제목의 투어 공연 — 도시(공연장)가 다르다
        save(SourceType.KOPIS, "난타", "울산문화예술회관", "울산");
        save(SourceType.CULTURE, "난타", "명보아트홀", "서울");
        // 같은 소스 안의 중복은 기관이 일부러 나눠 올린 것일 수 있어 건드리지 않는다
        save(SourceType.CULTURE, "[대학로] 전락", "대학로 TOM", "서울");
        save(SourceType.CULTURE, "전락 [대학로]", "대학로 TOM", "서울");

        assertThat(syncService.mergeCrossSourceDuplicates()).isZero();
        assertThat(performanceRepository.count()).isEqualTo(6);
    }

    @DisplayName("수집 때 더 풍부한 소스로 이미 있는 공연은 새로 만들지 않는다")
    @Test
    void syncSkipsLowerRankedDuplicate() {
        save(SourceType.KOPIS, "다이브 [대학로]", "링크더스페이스(LINK THE SPACE)", "-");

        ExternalPerformance culture = ExternalPerformance.builder()
                .externalId("CIA-test-dive")
                .sourceType(SourceType.CULTURE)
                .title("[대학로] 다이브")
                .genre("뮤지컬")
                .venue("링크더스페이스(LINK THE SPACE) 1관")
                .address("서울 종로구")
                .region("서울특별시")
                .startDate(START)
                .endDate(END)
                .build();

        PerformanceSyncService.SyncBatchResult result = syncService.sync(List.of(culture));

        assertThat(result.created()).isZero();
        assertThat(performanceRepository.count()).isEqualTo(1);
        assertThat(performanceRepository.findAll().get(0).getAddress()).isEqualTo("서울 종로구");
    }

    @DisplayName("지우려는 쪽 회차에 선점된 좌석이 있으면 합치지 않고 좌석도 그대로 둔다")
    @Test
    void keepsDuplicateWithHeldSeat() {
        save(SourceType.KOPIS, "다이브 [대학로]", "링크더스페이스(LINK THE SPACE)", "-");
        Performance culture = save(SourceType.CULTURE, "[대학로] 다이브", "링크더스페이스(LINK THE SPACE) 1관", "서울 종로구");
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(culture).showAt(LocalDateTime.now().plusDays(3)).totalSeats(2).remainingSeats(2).build());
        Seat held = Seat.builder().schedule(schedule).section("1층").rowNo(1).seatNo(1)
                .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000).build();
        held.hold();
        seatRepository.save(held);
        seatRepository.save(Seat.builder().schedule(schedule).section("1층").rowNo(1).seatNo(2)
                .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000).build());

        assertThat(syncService.mergeCrossSourceDuplicates()).isZero();
        assertThat(performanceRepository.existsById(culture.getId())).isTrue();
        assertThat(seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId())).hasSize(2);
    }

    @DisplayName("지우는 쪽의 회차와 좌석도 함께 지운다")
    @Test
    void removesSchedulesAndSeatsOfDuplicate() {
        save(SourceType.KOPIS, "다이브 [대학로]", "링크더스페이스(LINK THE SPACE)", "-");
        Performance culture = save(SourceType.CULTURE, "[대학로] 다이브", "링크더스페이스(LINK THE SPACE) 1관", "서울 종로구");
        PerformanceSchedule past = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(culture).showAt(LocalDateTime.now().minusDays(2)).totalSeats(1).remainingSeats(1).build());
        PerformanceSchedule future = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(culture).showAt(LocalDateTime.now().plusDays(2)).totalSeats(1).remainingSeats(1).build());
        for (PerformanceSchedule s : List.of(past, future)) {
            seatRepository.save(Seat.builder().schedule(s).section("1층").rowNo(1).seatNo(1)
                    .grade(SeatGrade.R).status(SeatStatus.AVAILABLE).price(50_000).build());
        }

        assertThat(syncService.mergeCrossSourceDuplicates()).isEqualTo(1);
        assertThat(performanceRepository.existsById(culture.getId())).isFalse();
        assertThat(scheduleRepository.findAllById(List.of(past.getId(), future.getId()))).isEmpty();
    }
}
