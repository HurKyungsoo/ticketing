package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.SeatRepository;
import com.portfolio.ticket.repository.WishlistRepository;
import com.portfolio.ticket.service.SeatGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 수집 → 정규화 → upsert → 회차/좌석 생성.
 *
 * API 원본에는 "회차" 개념이 없어서, 공연 기간 안에서 최대 8개까지 회차를
 * 생성하는 규칙을 두었다. 시각은 KOPIS 시간대별 상연 통계 분포를 따른다.
 * (README 의 데이터 가공 규칙 참고)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceSyncService {

    private static final int MAX_SCHEDULES = 8;
    private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

    /**
     * 이보다 기간이 긴 항목은 상설전시류(박물관 상설전시관 등)로 보고 제외한다.
     * 회차 생성이 startDate 기준 연속 8일이라, 기간이 몇 년씩 되는 항목은
     * 이미 다 지난 날짜로만 회차가 만들어져 예매가 불가능해진다.
     */
    private static final long MAX_RUN_DAYS = 90;

    /**
     * 공연이 아닌 항목의 제목 표지. 문화정보 소스에는 공연과 함께 참여자 모집 공고가 섞여 들어온다
     * ("[강북문화재단] 2026 블로그 서포터즈 2기 모집" 같은 것). 예매할 대상이 아니므로 수집하지 않는다.
     *
     * <p><b>넓게 잡지 않은 이유.</b> 실데이터 878건으로 후보를 세어보고 정했다 — "공모"는 5건 중
     * {@code 창작희곡공모 대상 수상작, 역행기}(연극), {@code 창작산실 대본공모 낭독공연}이 실제
     * 공연이었고, "교육"은 10건 중 {@code 브런치 콘서트 아트리움 … 청년교육단원}이 실제 콘서트였다.
     * 둘 다 넣었으면 진짜 공연이 조용히 사라졌을 것이다. 아래 세 구절은 현재 데이터에서
     * 모집 공고 5건을 전부 잡으면서 오탐이 하나도 없다.
     *
     * <p>제목으로 거르는 건 본질적으로 무딘 방법이라 <b>정확도 쪽에 맞췄다</b>. 놓치는 공고가
     * 생기더라도 진짜 공연을 지우는 것보다 낫다. 제외한 건은 로그로 남겨 확인할 수 있게 한다.
     */
    private static final List<String> NON_PERFORMANCE_MARKERS =
            List.of("참여자 모집", "참가자 모집", "서포터즈");

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final WishlistRepository wishlistRepository;
    private final SeatGenerator seatGenerator;
    private final PerformanceCategoryResolver categoryResolver;
    private final ShowTimeDistributionProperties showTimes;

    /** 한 페이지 분량 동기화 결과. skipped(이미 존재해 갱신만 함)는 received - created 로 구한다. */
    public record SyncBatchResult(int received, int created) {}

    @Transactional
    public SyncBatchResult sync(List<ExternalPerformance> externals) {
        int saved = 0;
        LocalDate today = LocalDate.now();

        for (ExternalPerformance external : externals) {
            // 모집 공고 등 예매 대상이 아닌 항목. 규칙이 생기기 전에 이미 들어온 건도 여기서 걷어낸다.
            if (isNotPerformance(external.getTitle())) {
                continue;
            }
            // 이미 종료된 공연은 화면에 노출되지 않으므로 회차/좌석까지 만들 필요가 없다.
            if (external.getEndDate().isBefore(today)) {
                continue;
            }
            if (ChronoUnit.DAYS.between(external.getStartDate(), external.getEndDate()) > MAX_RUN_DAYS) {
                continue;
            }

            Performance performance = performanceRepository.findByExternalId(external.getExternalId())
                    .orElse(null);

            if (performance == null) {
                performance = performanceRepository.save(toEntity(external));
                createSchedules(performance, external);
                saved++;
            } else {
                performance.updateFromExternal(
                        external.getTitle(), external.getGenre(), external.getVenue(),
                        external.getVenueFacilityId(), external.getVenueHallId(),
                        external.getAddress(), external.getLatitude(), external.getLongitude(),
                        external.getStartDate(), external.getEndDate(),
                        external.getPosterUrl(), external.getDescription(),
                        external.getAgeLimit(), external.getRunningTime(), external.getCastMembers(),
                        external.getTotalSeatCount(), external.getBasePrice(),
                        external.getSourceType(), categoryResolver.resolve(external.getGenre()),
                        external.getRegion());
            }
        }
        log.info("공연 동기화 완료. 수신={}, 신규={}", externals.size(), saved);
        return new SyncBatchResult(externals.size(), saved);
    }

    /** 제목에 모집 공고 표지가 들어 있으면 공연이 아니다. */
    private boolean isNotPerformance(String title) {
        return title != null && NON_PERFORMANCE_MARKERS.stream().anyMatch(title::contains);
    }

    /**
     * 이 규칙이 생기기 전에 이미 수집된 모집 공고를 걷어낸다.
     *
     * <p>수집 루프의 {@code continue} 만으로는 부족하다 — 그건 "다시 내려온" 항목만 막는다.
     * 이미 DB 에 있는 건은 API 응답에서 빠지는 순간 영영 남으므로, 저장된 제목을 직접 훑는다.
     * 멱등이라 동기화 때마다 돌려도 안전하다.
     *
     * <p>예매가 걸린 항목은 남긴다. 표를 산 사람에게 예매 내역은 영수증이라, 수집 규칙이
     * 바뀌었다고 그 기록을 지울 수는 없다.
     */
    @Transactional
    public int purgeNonPerformances() {
        List<Performance> targets = performanceRepository.findAll().stream()
                .filter(p -> isNotPerformance(p.getTitle()))
                .toList();

        int removed = 0;
        for (Performance performance : targets) {
            if (reservationRepository.existsByPerformanceId(performance.getId())) {
                log.warn("공연이 아닌 항목이지만 예매가 있어 남긴다. id={}, title={}",
                        performance.getId(), performance.getTitle());
                continue;
            }
            // 찜도 공연을 FK 로 참조한다. 찜은 사용자가 만든 기록이지만 공연 자체가 사라지면
            // 가리킬 대상이 없다 — 금전·입장 권리가 걸린 예매와 달라서 함께 지운다.
            wishlistRepository.deleteByPerformanceId(performance.getId());

            // 좌석은 회차에 매달려 있지만 회차 쪽에 컬렉션 매핑이 없어 cascade 가 닿지 않는다.
            // 먼저 지우지 않으면 FK 제약에 걸린다 (회차는 Performance 의 cascade 로 함께 지워진다).
            for (PerformanceSchedule schedule : scheduleRepository.findByPerformanceIdOrderByShowAtAsc(performance.getId())) {
                seatRepository.deleteAll(
                        seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(schedule.getId()));
            }
            performanceRepository.delete(performance);
            log.info("공연이 아닌 항목을 제거했다. title={}", performance.getTitle());
            removed++;
        }
        return removed;
    }

    private Performance toEntity(ExternalPerformance e) {
        return Performance.builder()
                .externalId(e.getExternalId())
                .sourceType(e.getSourceType())
                .title(e.getTitle())
                .genre(e.getGenre())
                .category(categoryResolver.resolve(e.getGenre()))
                .venue(e.getVenue())
                .venueFacilityId(e.getVenueFacilityId())
                .venueHallId(e.getVenueHallId())
                .address(e.getAddress())
                .region(e.getRegion())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .posterUrl(e.getPosterUrl())
                .description(e.getDescription())
                .ageLimit(e.getAgeLimit())
                .runningTime(e.getRunningTime())
                .castMembers(e.getCastMembers())
                .totalSeatCount(e.getTotalSeatCount())
                .basePrice(e.getBasePrice())
                .build();
    }

    private void createSchedules(Performance performance, ExternalPerformance external) {
        Map<DayOfWeek, List<LocalTime>> showTimesByDay = external.getShowTimesByDay();
        int totalSeats = performance.getTotalSeatCount() == null ? 200 : performance.getTotalSeatCount();

        int created = (showTimesByDay == null || showTimesByDay.isEmpty())
                ? createDefaultSchedules(performance, totalSeats)
                : createGuidedSchedules(performance, showTimesByDay, totalSeats);

        if (created == 0) {
            // dtguidance 가 있었지만 해당 기간에 매칭되는 요일이 하나도 없는 경우 등 (안전망)
            createDefaultSchedules(performance, totalSeats);
        }

        performanceRepository.flush();

        for (PerformanceSchedule schedule : performance.getSchedules()) {
            seatGenerator.generate(schedule.getId(), performance.getVenueHallId(), performance.getVenue(),
                    performance.getTotalSeatCount(), performance.getBasePrice(), external.getPricesByGrade());
        }
    }

    /**
     * 원본에 회차 개념이 없을 때 쓰는 대체 규칙: 공연 기간 내 최대 8일, 하루 한 회차.
     *
     * 시각은 KOPIS 시간대별 상연 통계의 실측 분포를 따른다(showtime-distribution.yml).
     * 설정이 없으면 예전 규칙인 19:00 고정으로 떨어진다.
     */
    private int createDefaultSchedules(Performance performance, int totalSeats) {
        LocalDate cursor = performance.getStartDate();
        LocalDate end = performance.getEndDate();
        int created = 0;

        while (!cursor.isAfter(end) && created < MAX_SCHEDULES) {
            addSchedule(performance, LocalDateTime.of(cursor, resolveShowTime(performance, cursor)), totalSeats);
            created++;
            cursor = cursor.plusDays(1);
        }
        return created;
    }

    /**
     * 공연과 날짜로 seed 를 만들어 분포에서 시각을 고른다.
     * 같은 공연의 같은 날짜면 항상 같은 시각이 나오므로, 배치를 다시 돌려도 결과가 흔들리지 않는다.
     */
    private LocalTime resolveShowTime(Performance performance, LocalDate date) {
        long seed = 31L * performance.getExternalId().hashCode() + date.toEpochDay();
        LocalTime picked = showTimes.pick(seed);
        return picked != null ? picked : SHOW_TIME;
    }

    /** KOPIS dtguidance 파싱 결과(요일별 실제 공연시간)로 회차를 만든다. 하루에 여러 회차(마티네/저녁)도 반영. */
    private int createGuidedSchedules(Performance performance, Map<DayOfWeek, List<LocalTime>> showTimesByDay,
                                       int totalSeats) {
        LocalDate cursor = performance.getStartDate();
        LocalDate end = performance.getEndDate();
        int created = 0;

        while (!cursor.isAfter(end) && created < MAX_SCHEDULES) {
            List<LocalTime> times = showTimesByDay.get(cursor.getDayOfWeek());
            if (times != null) {
                for (LocalTime time : times) {
                    if (created >= MAX_SCHEDULES) break;
                    addSchedule(performance, LocalDateTime.of(cursor, time), totalSeats);
                    created++;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return created;
    }

    private void addSchedule(Performance performance, LocalDateTime showAt, int totalSeats) {
        performance.addSchedule(PerformanceSchedule.builder()
                .showAt(showAt)
                .totalSeats(totalSeats)
                .remainingSeats(totalSeats)
                .build());
    }
}
