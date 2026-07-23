package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.service.SeatGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 수집 → 정규화 → upsert → 회차/좌석 생성.
 *
 * API 원본에는 "회차" 개념이 없어서, 공연 기간 안에서 주말 19:00 회차를
 * 최대 8개까지 생성하는 규칙을 두었다. (README 의 데이터 가공 규칙 참고)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceSyncService {

    private static final int MAX_SCHEDULES = 8;
    private static final LocalTime SHOW_TIME = LocalTime.of(19, 0);

    private final PerformanceRepository performanceRepository;
    private final SeatGenerator seatGenerator;

    @Transactional
    public int sync(List<ExternalPerformance> externals) {
        int saved = 0;

        for (ExternalPerformance external : externals) {
            Performance performance = performanceRepository.findByExternalId(external.getExternalId())
                    .orElse(null);

            if (performance == null) {
                performance = performanceRepository.save(toEntity(external));
                createSchedules(performance);
                saved++;
            } else {
                performance.updateFromExternal(
                        external.getTitle(), external.getGenre(), external.getVenue(),
                        external.getAddress(), external.getStartDate(), external.getEndDate(),
                        external.getPosterUrl(), external.getTotalSeatCount(), external.getBasePrice());
            }
        }
        log.info("공연 동기화 완료. 수신={}, 신규={}", externals.size(), saved);
        return saved;
    }

    private Performance toEntity(ExternalPerformance e) {
        return Performance.builder()
                .externalId(e.getExternalId())
                .title(e.getTitle())
                .genre(e.getGenre())
                .venue(e.getVenue())
                .address(e.getAddress())
                .latitude(e.getLatitude())
                .longitude(e.getLongitude())
                .startDate(e.getStartDate())
                .endDate(e.getEndDate())
                .posterUrl(e.getPosterUrl())
                .totalSeatCount(e.getTotalSeatCount())
                .basePrice(e.getBasePrice())
                .build();
    }

    private void createSchedules(Performance performance) {
        LocalDate cursor = performance.getStartDate();
        LocalDate end = performance.getEndDate();
        int totalSeats = performance.getTotalSeatCount() == null ? 200 : performance.getTotalSeatCount();
        int created = 0;

        while (!cursor.isAfter(end) && created < MAX_SCHEDULES) {
            PerformanceSchedule schedule = PerformanceSchedule.builder()
                    .showAt(LocalDateTime.of(cursor, SHOW_TIME))
                    .totalSeats(totalSeats)
                    .remainingSeats(totalSeats)
                    .build();

            performance.addSchedule(schedule);
            created++;
            cursor = cursor.plusDays(1);
        }

        performanceRepository.flush();

        for (PerformanceSchedule schedule : performance.getSchedules()) {
            seatGenerator.generate(schedule.getId(),
                    performance.getTotalSeatCount(), performance.getBasePrice());
        }
    }
}
