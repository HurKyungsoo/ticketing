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

    private Performance save(SourceType source, String title, String venue, LocalDate start, LocalDate end) {
        return performanceRepository.save(Performance.builder()
                .externalId(source.name() + "-" + title + "-" + System.nanoTime())
                .sourceType(source)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue(venue)
                .region("서울특별시")
                .startDate(start)
                .endDate(end)
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    /** 2026-11-13 운영 DB 에서 기본 규칙이 놓친 실제 쌍들. 기간은 둘이 정확히 같다. */
    @DisplayName("기간이 정확히 같으면 제목 앞뒤 장르·연도, 다른 지역 태그, 다른 공연장 표기를 넘어 합친다")
    @Test
    void mergesLooseMatchesWithSameDates() {
        String[][] pairs = {
                // 공연장 개명 — 괄호 속 옛 이름
                {"난세: 정도전과 이방원의 이야기 [대학로]", "NOL 서경스퀘어(구. 서경대학교 공연예술센터)",
                        "[대학로] 난세: 정도전과 이방원의 이야기", "서경대학교 공연예술센터"},
                // 공연장 개명 — 핵심 이름만 남음
                {"FATED [대학로]", "링크아트센터드림", "[대학로] FATED", "대학로 드림아트센터"},
                // 상위 시설 이름
                {"에너미", "세종문화회관", "에너미", "세종M씨어터"},
                // 장르 접두어 + 꺾쇠, 지역 태그가 KOPIS 쪽에만
                {"범죄의 재구성 [대전]", "아신극장", "연극 <범죄의 재구성>", "아신극장 2관 (2층)"},
                // 연도 접두어
                {"집에 사는 몬스터", "LG아트센터 서울", "2026 집에 사는 몬스터", "LG아트센터 서울 U+ 스테이지"},
                // 뒤에 붙은 설명
                {"곤 투모로우 [대학로]", "홍익대 대학로 아트센터", "[대학로] 뮤지컬 <곤 투모로우> 10주년 기념 공연", "홍익대 대학로 아트센터 대극장"},
                // 지역 태그가 서로 포함 관계
                {"굿프렌즈 [대구]", "아트플러스씨어터", "[대구 동성로] 연극 <굿프렌즈>", "아트플러스씨어터"},
        };
        LocalDate start = LocalDate.now().minusDays(5);
        for (int i = 0; i < pairs.length; i++) {
            LocalDate end = LocalDate.now().plusDays(30 + i);
            save(SourceType.KOPIS, pairs[i][0], pairs[i][1], start, end);
            save(SourceType.CULTURE, pairs[i][2], pairs[i][3], start, end);
        }

        assertThat(syncService.mergeCrossSourceDuplicates()).isEqualTo(pairs.length);
        assertThat(performanceRepository.findAll()).extracting(Performance::getSourceType).containsOnly(SourceType.KOPIS);
        assertThat(syncService.mergeCrossSourceDuplicates()).isZero();   // 멱등
    }

    /** 느슨한 규칙의 오탐 확인 — 실데이터에서 제목이 비슷했지만 다른 공연이던 쌍. */
    @DisplayName("느슨한 규칙도 기간이 다르거나, 공연장이 다르거나, 상품 태그가 다르면 합치지 않는다")
    @Test
    void looseRuleKeepsDifferentShows() {
        LocalDate start = LocalDate.now().minusDays(5);
        LocalDate end = LocalDate.now().plusDays(20);
        // 같은 제목의 투어 공연 — 도시마다 기간이 다르다
        save(SourceType.CULTURE, "[서울 양천] 2026 라이브 가족뮤지컬 호두까기 인형", "한국방송회관 코바코홀", start, end);
        save(SourceType.KOPIS, "호두까기 인형 [부산]", "동래문화회관", start.plusDays(9), start.plusDays(10));
        // 같은 제목, 다른 도시 — 기간도 다르다
        save(SourceType.CULTURE, "보잉보잉", "대학로 스타릿홀", start, end);
        save(SourceType.KOPIS, "보잉보잉 [대구]", "송죽씨어터", start.plusDays(6), end.plusDays(40));
        // 기간이 같아도 공연장 핵심 이름이 안 겹치면 다른 공연이다
        save(SourceType.CULTURE, "어린이 뮤지컬 미녀와 야수", "창원과학체험관", start.plusDays(1), end);
        save(SourceType.KOPIS, "미녀와 야수 [구미]", "롯데마트 어린이소극장 [구미]", start.plusDays(1), end);
        // 기간·공연장이 같아도 상품 태그가 전혀 다르면 다른 상품이다
        save(SourceType.KOPIS, "제1회 ST-NOVA [일반 패키지]", "서울연극창작센터", start.plusDays(2), end);
        save(SourceType.CULTURE, "제1회 ST-NOVA [서울연극협회원 패키지]", "서울연극창작센터", start.plusDays(2), end);
        // 시리즈 전체와 그중 한 회 — 기간이 다르다
        save(SourceType.CULTURE, "[마포문화재단] [M 마티네] 〈MAC 모닝 콘서트 #7~10〉", "마포아트센터 아트홀맥", start, end);
        save(SourceType.KOPIS, "MAC 모닝 콘서트 #10", "마포아트센터", end, end);

        assertThat(syncService.mergeCrossSourceDuplicates()).isZero();
        assertThat(performanceRepository.count()).isEqualTo(10);
    }
}
