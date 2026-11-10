package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SeatStatus;
import com.portfolio.ticket.repository.NotificationRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.RestockSubscriptionRepository;
import com.portfolio.ticket.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 회차·좌석 재생성의 트랜잭션 단위. 회차 하나를 지우는 것과 공연 하나에 회차를 채우는 것을
 * 각각 따로 커밋한다 — 하나가 실패해도 나머지는 남고, 락을 오래 쥐지 않는다.
 * 순서와 대상 선정은 {@link ScheduleRegeneration} 이 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleRebuildService {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final RestockSubscriptionRepository restockSubscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final SeatGenerator seatGenerator;

    /**
     * 아무도 손대지 않은 회차면 좌석과 함께 지운다.
     *
     * <p><b>락 순서는 Seat → PerformanceSchedule</b> 이다(CLAUDE.md 동시성 규칙 1). 좌석을 먼저 전부
     * 잠그고 모두 비어 있는지 본 다음에 회차를 잠근다. 좌석 선점(ReservationService)도 좌석을 먼저
     * 잡으므로, 두 작업이 서로를 기다리며 멈추는 일이 없다. 잠근 뒤에 확인하므로 확인과 삭제 사이에
     * 누가 좌석을 잡을 틈도 없다.
     *
     * @return 지웠으면 true. 좌석이 하나라도 팔렸거나 잡혀 있거나, 예매·빈자리 알림·알림이 걸려 있으면 false.
     */
    @Transactional
    public boolean removeIfUntouched(Long scheduleId) {
        List<Seat> seats = seatRepository.findAllByScheduleIdForUpdate(scheduleId);
        if (seats.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE)) {
            return false;
        }
        PerformanceSchedule schedule = scheduleRepository.findByIdForUpdate(scheduleId).orElse(null);
        if (schedule == null) {
            return false;
        }
        if (reservationRepository.existsByScheduleId(scheduleId)
                || restockSubscriptionRepository.existsByScheduleId(scheduleId)
                || notificationRepository.existsByScheduleId(scheduleId)) {
            return false;
        }
        seatRepository.deleteAllInBatch(seats);
        scheduleRepository.delete(schedule);
        return true;
    }

    /**
     * 원하는 시각 중 아직 없는 회차를 만들고 좌석을 실제 가격으로 채운 뒤, 다시 만든 날을 남긴다.
     * 지우지 못하고 남은 회차(예매가 있는 회차)와 시각이 같으면 새로 만들지 않는다.
     *
     * @return 새로 만든 회차 수
     */
    @Transactional
    public int addMissingAndMark(Long performanceId, List<LocalDateTime> wanted,
                                 Map<SeatGrade, Integer> pricesByGrade, LocalDateTime from) {
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) {
            return 0;
        }
        Set<LocalDateTime> existing = new HashSet<>(scheduleRepository.findShowAtsFrom(performanceId, from));
        int totalSeats = performance.getTotalSeatCount() == null ? 200 : performance.getTotalSeatCount();

        List<PerformanceSchedule> created = new ArrayList<>();
        for (LocalDateTime showAt : wanted) {
            if (existing.contains(showAt)) continue;
            PerformanceSchedule schedule = PerformanceSchedule.builder()
                    .showAt(showAt)
                    .totalSeats(totalSeats)
                    .remainingSeats(totalSeats)
                    .build();
            performance.addSchedule(schedule);
            created.add(schedule);
        }
        performanceRepository.flush();

        for (PerformanceSchedule schedule : created) {
            seatGenerator.generate(schedule.getId(), performance.getVenueHallId(), performance.getVenue(),
                    performance.getTotalSeatCount(), performance.getBasePrice(), pricesByGrade);
        }
        performance.markSchedulesRebuilt(LocalDate.now());
        return created.size();
    }

    /** 원문을 해석할 수 없거나 남은 기간에 맞는 요일이 없어 손대지 않을 공연. 다시 고르지 않게 표시만 한다. */
    @Transactional
    public void markSkipped(Long performanceId) {
        performanceRepository.findById(performanceId).ifPresent(p -> p.markSchedulesRebuilt(LocalDate.now()));
    }

    @Transactional(readOnly = true)
    public Performance load(Long performanceId) {
        return performanceRepository.findById(performanceId).orElse(null);
    }
}
