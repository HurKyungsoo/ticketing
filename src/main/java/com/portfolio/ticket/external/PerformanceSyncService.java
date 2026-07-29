package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.repository.PerformanceRepository;
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

    private final PerformanceRepository performanceRepository;
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

    private Performance toEntity(ExternalPerformance e) {
        return Performance.builder()
                .externalId(e.getExternalId())
                .sourceType(e.getSourceType())
                .title(e.getTitle())
                .genre(e.getGenre())
                .category(categoryResolver.resolve(e.getGenre()))
                .venue(e.getVenue())
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
            seatGenerator.generate(schedule.getId(), performance.getVenue(),
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
