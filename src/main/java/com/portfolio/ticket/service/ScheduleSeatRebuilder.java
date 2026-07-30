package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SeatStatus;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 회차 하나의 좌석을 지우고 현재 규칙으로 다시 만든다.
 *
 * {@link SeatRegenerationService} 와 분리된 빈인 이유는 트랜잭션 때문이다. 같은 빈 안에서
 * 호출하면 프록시를 거치지 않아 @Transactional 이 무시된다. 회차별로 트랜잭션을 끊어야
 * 수천 건 중 하나가 실패해도 나머지가 살아남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleSeatRebuilder {

    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final SeatGenerator seatGenerator;

    public record Result(boolean rebuilt, long before, long after) {

        static Result skipped(long seats) {
            return new Result(false, seats, seats);
        }
    }

    @Transactional
    public Result rebuild(Long scheduleId) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) return Result.skipped(0);

        List<Seat> existing = seatRepository.findByScheduleIdOrderBySectionAscRowNoAscSeatNoAsc(scheduleId);
        long before = existing.size();

        // 선점·판매된 좌석이 하나라도 있으면 건드리지 않는다.
        // 예매가 가리키는 좌석을 지우면 사용자의 예매 내역이 깨진다.
        if (existing.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE)) {
            log.debug("예매가 있는 회차는 건너뛴다. scheduleId={}", scheduleId);
            return Result.skipped(before);
        }

        Map<SeatGrade, Integer> pricesByGrade = capturePrices(existing);

        seatRepository.deleteAll(existing);
        seatRepository.flush();

        Performance performance = schedule.getPerformance();
        int created = seatGenerator.generate(scheduleId, performance.getVenueHallId(), performance.getVenue(),
                performance.getTotalSeatCount(), performance.getBasePrice(), pricesByGrade);

        return new Result(true, before, created);
    }

    /**
     * 기존 좌석에서 등급별 가격을 읽어둔다. 이걸 넘기지 않으면 KOPIS pcseguidance 로 받아둔
     * 실제 등급 가격이 basePrice 비율 계산으로 대체된다. 같은 등급은 동일 가격으로 생성되므로
     * 등급별 최댓값 하나면 충분하다.
     */
    private Map<SeatGrade, Integer> capturePrices(List<Seat> seats) {
        Map<SeatGrade, Integer> prices = new EnumMap<>(SeatGrade.class);
        for (Seat seat : seats) {
            prices.merge(seat.getGrade(), seat.getPrice(), Math::max);
        }
        return prices.isEmpty() ? null : prices;
    }
}
