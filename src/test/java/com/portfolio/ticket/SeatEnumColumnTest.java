package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.GradePriceRow;
import com.portfolio.ticket.mapper.dto.SeatMapRow;
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
 * 좌석의 등급·상태를 JPA 로 쓰고 MyBatis 로 읽었을 때 같은 값이 나오는지.
 *
 * <p>이 프로젝트는 좌석을 JPA 로 저장하고(엔티티 {@code Seat}) 좌석 배치도는 MyBatis 로
 * 읽는다(성능 때문에 나눠 둔 경계다). 그 경계에서 값이 달라지면 화면만 조용히 깨진다 —
 * 저장은 정상이고 예매도 정상인데 배치도의 등급색·가격·선택 가능 여부만 어긋난다.
 *
 * <p>이 경계가 특히 미끄러운 이유는 컬럼 타입 때문이다. {@code @Enumerated(EnumType.STRING)}
 * 이라 값은 이름으로 들어가지만, Hibernate 가 만든 DDL(그대로 Flyway V1 이 됐다)은 H2 에서
 * 컬럼 타입을 {@code varchar} 가 아니라 {@code enum('A','R','S','VIP')} 로 잡는다.
 * H2 의 ENUM 은 라벨과 1-기반 순번을 둘 다 갖는 타입이라, 읽는 쪽이 어느 쪽을 집어오는지가
 * 드라이버·타입핸들러에 달려 있다 — 순번을 집어오면 "VIP" 대신 "4" 가 돌아온다.
 *
 * <p>그렇게 되면 두 가지가 한꺼번에 깨진다.
 * <ul>
 *   <li>등급: 화면 클래스가 {@code grade-4} 가 되어 등급색 규칙(.grade-VIP 등)에 하나도
 *       안 걸리고, 상세의 관람료가 "4 75,000원" 으로 나온다.
 *   <li>상태: {@code SeatMapRow.isAvailable()} 이 {@code "AVAILABLE".equals(status)} 라
 *       항상 false 가 된다 — <b>모든 좌석이 "선택할 수 없는 좌석" 이 되어 예매가 막힌다.</b>
 * </ul>
 *
 * <p>화면을 안 보면 안 드러나고, 엔티티만 보면 멀쩡해 보인다. 그래서 여기서 고정한다.
 *
 * <p><b>이 테스트를 쓰게 된 경위.</b> 개발 중 실행 중이던 서버가 정확히 위 증상을 냈다
 * (배치도 전 좌석이 "선택할 수 없는 좌석", 상세 관람료가 "4 75,000원"). 원인을 좁히려고
 * 이 테스트를 먼저 짰는데 <b>처음부터 통과했고</b>, DB 를 직접 조회해도 값은 멀쩡했다
 * ({@code grade='VIP'} 30건, 전 좌석 AVAILABLE). 결국 원인은 매핑이 아니라 stale 빌드
 * 산출물이었다 — {@code ./gradlew clean} 후 재기동하니 그대로 정상이 됐다
 * (CLAUDE.md 가 이 리포에서 반복됐다고 적어둔 그 증상이다).
 *
 * <p>그래서 이 테스트는 실재했던 버그를 막는 게 아니라, <b>언젠가 실재할 수 있는 경계를</b>
 * 고정한다. 위 증상을 다시 만나면 여기가 먼저 답을 준다 — 통과하면 매핑은 결백하니
 * 빌드 산출물부터 의심하면 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatEnumColumnTest {

    @Autowired SeatMapper seatMapper;
    @Autowired MemberRepository memberRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private PerformanceSchedule schedule;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("ENUM-" + System.nanoTime())
                .title("좌석 등급 매핑 테스트")
                .venue("테스트홀")
                .startDate(LocalDate.now().plusDays(10))
                .endDate(LocalDate.now().plusDays(11))
                .totalSeatCount(4)
                .basePrice(40_000)
                .build());

        schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(10).withHour(19).withMinute(0).withSecond(0).withNano(0))
                .totalSeats(4)
                .remainingSeats(4)
                .build());

        // 등급 네 개를 한 줄에 나란히. 가격은 등급 순서와 반대로 둬서, 혹시 순번을
        // 읽더라도 가격과 우연히 맞아떨어지지 않게 한다.
        saveSeat(1, SeatGrade.VIP, 75_000);
        saveSeat(2, SeatGrade.R, 60_000);
        saveSeat(3, SeatGrade.S, 50_000);
        saveSeat(4, SeatGrade.A, 40_000);
    }

    private void saveSeat(int seatNo, SeatGrade grade, int price) {
        seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("A")
                .rowNo(1)
                .seatNo(seatNo)
                .grade(grade)
                .price(price)
                .build());
    }

    @DisplayName("좌석 배치도의 등급이 순번이 아니라 이름으로 읽힌다 (H2 enum 컬럼)")
    @Test
    void seatMapReadsGradeAsName() {
        List<SeatMapRow> rows = seatMapper.selectSeatMap(schedule.getId());

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(SeatMapRow::getGrade)
                .containsExactly("VIP", "R", "S", "A");
    }

    @DisplayName("좌석 상태도 이름으로 읽힌다 — 안 그러면 모든 좌석이 선택 불가가 된다")
    @Test
    void seatMapReadsStatusAsName() {
        List<SeatMapRow> rows = seatMapper.selectSeatMap(schedule.getId());

        assertThat(rows).extracting(SeatMapRow::getStatus)
                .containsOnly("AVAILABLE");
        // 이 화면이 실제로 쓰는 판정. 위가 깨지면 여기서 예매가 통째로 막힌다.
        assertThat(rows).allMatch(SeatMapRow::isAvailable);
    }

    @DisplayName("상세 화면의 등급별 관람료도 이름으로 읽힌다")
    @Test
    void gradePricesReadGradeAsName() {
        List<GradePriceRow> rows = seatMapper.selectGradePrices(schedule.getId());

        // 쿼리가 price DESC 라 VIP -> R -> S -> A 순이다.
        assertThat(rows).extracting(GradePriceRow::getGrade)
                .containsExactly("VIP", "R", "S", "A");
    }
}
