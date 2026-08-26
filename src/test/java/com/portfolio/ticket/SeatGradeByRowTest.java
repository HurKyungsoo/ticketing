package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatHoldRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.service.SeatGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석도 설정이 없는 기본 생성 홀({@code SeatGenerator.generateDefault})의 등급 배정.
 *
 * <p>배포본을 훑다 발견한 것에서 출발했다 — <b>B구역 2열이 앞 3석 VIP(75,000원) / 뒤 5석
 * R(60,000원)로 갈려 있었다.</b> 등급을 좌석 단위 index 로 정하다 보니 경계가 줄 한가운데를
 * 지났고, 한 줄 안에서 왼쪽부터 순서대로 등급이 잘리는 모양이 나왔다. 실제 극장에는 없는
 * 모양이다(중앙/사이드로 갈리는 홀은 있어도 좌→우로 잘리지는 않는다).
 *
 * <p>이 테스트가 지키는 것은 두 가지다. ① 같은 층·같은 줄이면 등급이 하나다. ② 앞줄이
 * 뒷줄보다 나쁘지 않다. 둘 다 "동작은 하는데 이상하다"를 결함으로 보는 규칙에 걸린다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatGradeByRowTest {

    @Autowired SeatGenerator seatGenerator;
    @Autowired SeatRepository seatRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

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

    /**
     * 실제로 깨져 있던 크기(200석)를 그대로 쓴다. 한 줄 20석 × 10줄이라 등급 경계(15% =
     * 30번째 좌석)가 2열 한가운데에 떨어진다 — 종전 방식이 반드시 줄을 가르는 배치다.
     */
    @DisplayName("한 줄은 등급이 하나다 — 줄 가운데서 갈리지 않는다")
    @Test
    void everyRowHasExactlyOneGrade() {
        List<Seat> seats = generate(200);

        Map<String, Set<SeatGrade>> gradesByRow = seats.stream().collect(Collectors.groupingBy(
                SeatGradeByRowTest::rowKey,
                TreeMap::new,
                Collectors.mapping(Seat::getGrade, Collectors.toSet())));

        assertThat(gradesByRow).isNotEmpty();
        assertThat(gradesByRow).allSatisfy((row, grades) ->
                assertThat(grades).as("%s 의 등급", row).hasSize(1));
    }

    /**
     * 위 테스트만 있으면 "전부 A석"으로도 통과한다. 등급이 실제로 여러 개 쓰이고,
     * 그게 <b>줄 번호를 따라 단조롭게</b> 나빠지는지 같이 본다 — 앞줄이 뒷줄보다 나쁘면
     * 좌석도를 아무리 예쁘게 그려도 값이 틀린 것이다.
     */
    @DisplayName("등급은 앞줄에서 뒷줄로 갈수록 나빠지기만 한다")
    @Test
    void gradeNeverImprovesTowardTheBack() {
        List<Seat> seats = generate(200);

        // 층 이름 -> (줄 번호 -> 등급). 층이 갈리면 줄 번호가 다시 1부터 시작하므로 층별로 본다.
        Map<String, Map<Integer, SeatGrade>> byFloor = new LinkedHashMap<>();
        for (Seat seat : seats) {
            byFloor.computeIfAbsent(floorOf(seat), k -> new TreeMap<>())
                    .put(seat.getRowNo(), seat.getGrade());
        }

        assertThat(seats).extracting(Seat::getGrade).contains(SeatGrade.VIP, SeatGrade.A);

        byFloor.forEach((floor, rows) -> {
            SeatGrade previous = null;
            for (Map.Entry<Integer, SeatGrade> entry : rows.entrySet()) {
                if (previous != null) {
                    // enum 선언 순서가 곧 좋은 순서다(VIP·R·S·A). ordinal 이 줄면 뒷줄이
                    // 더 좋아진 것이다.
                    assertThat(entry.getValue().ordinal())
                            .as("%s %d열 등급이 앞줄보다 좋아졌다", floor, entry.getKey())
                            .isGreaterThanOrEqualTo(previous.ordinal());
                }
                previous = entry.getValue();
            }
        });
    }

    /**
     * 값이 등급을 따라간다는 것까지 확인한다 — 등급만 맞고 가격이 좌석마다 따로 놀면
     * 화면에서는 여전히 한 줄이 두 가격으로 보인다(원래 발견된 증상이 그것이다).
     */
    @DisplayName("한 줄은 가격도 하나다")
    @Test
    void everyRowHasExactlyOnePrice() {
        List<Seat> seats = generate(200);

        Map<String, Set<Integer>> pricesByRow = seats.stream().collect(Collectors.groupingBy(
                SeatGradeByRowTest::rowKey,
                TreeMap::new,
                Collectors.mapping(Seat::getPrice, Collectors.toSet())));

        assertThat(pricesByRow).allSatisfy((row, prices) ->
                assertThat(prices).as("%s 의 가격", row).hasSize(1));
    }

    /**
     * 줄이 20으로 나누어떨어지지 않는 크기. 마지막 줄이 짧아도 그 줄 안에서 갈리면 안 된다
     * — 줄 시작 좌석을 index 로 되짚는 계산(index - col)이 마지막 줄에서도 맞는지 본다.
     */
    @DisplayName("마지막 줄이 짧아도 그 줄 안에서 갈리지 않는다")
    @Test
    void partialLastRowIsStillUniform() {
        List<Seat> seats = generate(150);

        Map<String, Set<SeatGrade>> gradesByRow = seats.stream().collect(Collectors.groupingBy(
                SeatGradeByRowTest::rowKey,
                TreeMap::new,
                Collectors.mapping(Seat::getGrade, Collectors.toSet())));

        assertThat(gradesByRow).allSatisfy((row, grades) ->
                assertThat(grades).as("%s 의 등급", row).hasSize(1));
    }

    /**
     * 여러 층으로 갈리는 규모. 층이 바뀌면 줄 번호가 1부터 다시 시작하는데, 그 경계에서
     * 줄 시작을 잘못 짚으면 층 첫 줄만 갈린다.
     */
    @DisplayName("층이 여럿이어도 각 층의 모든 줄이 등급 하나다")
    @Test
    void multiFloorRowsAreUniform() {
        List<Seat> seats = generate(1200);

        Map<String, Set<SeatGrade>> gradesByRow = seats.stream().collect(Collectors.groupingBy(
                SeatGradeByRowTest::rowKey,
                TreeMap::new,
                Collectors.mapping(Seat::getGrade, Collectors.toSet())));

        assertThat(gradesByRow.size()).as("층이 여럿인 규모여야 의미가 있다").isGreaterThan(10);
        assertThat(gradesByRow).allSatisfy((row, grades) ->
                assertThat(grades).as("%s 의 등급", row).hasSize(1));
    }

    /** "1층 A" 처럼 구역까지 들어간 이름에서 층만 남긴다(구역이 달라도 같은 줄이다). */
    private static String floorOf(Seat seat) {
        String section = seat.getSection();
        int space = section.lastIndexOf(' ');
        return space < 0 ? "" : section.substring(0, space);
    }

    private static String rowKey(Seat seat) {
        return floorOf(seat) + "|" + seat.getRowNo() + "열";
    }

    /**
     * 좌석도 설정이 없는 공연장으로 만든다 — 공연장명·홀ID 를 안 주면
     * {@code venue-layouts.yml} 매칭에 실패해 generateDefault 로 떨어진다.
     */
    private List<Seat> generate(int totalSeatCount) {
        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("GRADE-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("등급 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("좌석도가 없는 테스트 공연장")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(totalSeatCount)
                .basePrice(50_000)
                .build());

        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(1).withNano(0))
                .totalSeats(totalSeatCount)
                .remainingSeats(totalSeatCount)
                .build());

        seatGenerator.generate(schedule.getId(), null, performance.getVenue(),
                totalSeatCount, performance.getBasePrice(), null);

        return seatRepository.findAll();
    }
}
