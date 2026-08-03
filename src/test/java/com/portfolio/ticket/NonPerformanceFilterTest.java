package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모집 공고 걸러내기.
 *
 * <p>문화정보 소스에는 공연과 함께 참여자 모집 공고가 섞여 들어온다. 목록에 묻혀 있을 때는
 * 눈에 안 띄었지만, 홈의 「곧 막을 내려요」 첫 칸이 "블로그 서포터즈 2기 모집"이 되면서 드러났다.
 *
 * <p><b>이 테스트의 핵심은 오탐 쪽이다.</b> 제목으로 거르는 건 무딘 방법이라, 넓게 잡으면
 * 진짜 공연이 예외도 없이 조용히 사라진다. 실데이터에서 실제로 위험했던 제목들
 * (창작희곡<b>공모</b> 대상 수상작 / 청년<b>교육</b>단원)을 그대로 넣어 살아남는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class NonPerformanceFilterTest {

    @Autowired PerformanceSyncService syncService;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        // 찜은 공연을 FK 로 참조하므로 공연보다 먼저 지워야 한다.
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    private Performance save(String title) {
        return performanceRepository.save(Performance.builder()
                .externalId("NP-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.CULTURE)
                .title(title)
                .category(PerformanceCategory.ETC)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(10))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    @DisplayName("모집 공고는 제거된다")
    @Test
    void purgesRecruitmentNotices() {
        save("[강북문화재단] 2026 블로그 서포터즈 2기 모집");
        save("[마포문화재단] 2026 꿈의 스튜디오 마포 - 예술 교육 프로그램 참여자 모집");
        save("[서초문화원] 2026년 『청년 문화 살롱』 참가자 모집");

        int purged = syncService.purgeNonPerformances();

        assertThat(purged).isEqualTo(3);
        assertThat(performanceRepository.count()).isZero();
    }

    /**
     * 오탐 확인. 아래 제목들은 전부 실데이터에 있는 <b>진짜 공연</b>이다.
     * "공모"나 "교육"을 표지에 넣었다면 여기서 사라졌을 것이다.
     */
    @DisplayName("제목에 공모·교육이 들어간 실제 공연은 남는다")
    @Test
    void keepsRealPerformancesWithSimilarWords() {
        save("창작희곡공모 대상 수상작, 역행기");
        save("여름, 낭독시즌: 창작산실 대본공모 낭독공연");
        save("브런치 콘서트 아트리움: 국립예술단체 영아티스트 시리즈, 국립국악관현악단 청년교육단원");
        save("2026 드영미술관 청년작가 공모선정전 《기록되지 않은 기록》");

        int purged = syncService.purgeNonPerformances();

        assertThat(purged).isZero();
        assertThat(performanceRepository.count()).isEqualTo(4);
    }

    @DisplayName("멱등이다 — 두 번 돌려도 두 번째는 지울 게 없다")
    @Test
    void isIdempotent() {
        save("[강북문화재단] 2026 블로그 서포터즈 2기 모집");
        save("정상 공연");

        assertThat(syncService.purgeNonPerformances()).isEqualTo(1);
        assertThat(syncService.purgeNonPerformances()).isZero();
        assertThat(performanceRepository.count()).isEqualTo(1);
    }

    @DisplayName("회차·좌석이 딸린 항목도 FK 위반 없이 지워진다")
    @Test
    void removesSchedulesAndSeatsToo() {
        Performance notice = save("2026 블로그 서포터즈 2기 모집");
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(notice)
                .showAt(LocalDateTime.now().plusDays(3))
                .totalSeats(10)
                .remainingSeats(10)
                .build());
        seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층").rowNo(1).seatNo(1)
                .grade(SeatGrade.A).status(SeatStatus.AVAILABLE).price(10_000)
                .build());

        assertThat(syncService.purgeNonPerformances()).isEqualTo(1);
        assertThat(performanceRepository.count()).isZero();
        assertThat(scheduleRepository.count()).isZero();
        assertThat(seatRepository.count()).isZero();
    }

    /**
     * 예매가 걸려 있으면 남긴다. 표를 산 사람에게 예매 내역은 영수증이라,
     * 수집 규칙이 바뀌었다는 이유로 그 기록을 지울 수는 없다.
     */
    @DisplayName("예매가 있으면 지우지 않는다")
    @Test
    void keepsItemsThatHaveReservations() {
        Performance notice = save("2026 블로그 서포터즈 2기 모집");
        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(notice)
                .showAt(LocalDateTime.now().plusDays(3))
                .totalSeats(10)
                .remainingSeats(9)
                .build());
        reservationRepository.save(Reservation.builder()
                .reservationNo("260803" + System.nanoTime() % 10_000_000)
                .memberId(1L)
                .schedule(schedule)
                .seatSummary("1층 1열 1번 A")
                .status(ReservationStatus.CONFIRMED)
                .amount(10_000)
                .createdAt(LocalDateTime.now())
                .holdExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build());

        assertThat(syncService.purgeNonPerformances()).isZero();
        assertThat(performanceRepository.count()).isEqualTo(1);
    }
}
