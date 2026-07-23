package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 공공데이터의 객석수(totalSeatCount)를 받아 구역/등급별 좌석을 생성한다.
 *
 * 배치 규칙: 한 줄 20석 고정, 앞에서부터
 *   상위 15% VIP / 다음 25% R / 다음 35% S / 나머지 A
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatGenerator {

    private static final int SEATS_PER_ROW = 20;
    private static final int DEFAULT_SEAT_COUNT = 200;
    private static final int DEFAULT_BASE_PRICE = 50_000;

    private final SeatRepository seatRepository;
    private final PerformanceScheduleRepository scheduleRepository;

    @Transactional
    public int generate(Long scheduleId, Integer totalSeatCount, Integer basePrice) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("회차를 찾을 수 없습니다. id=" + scheduleId));

        if (seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE) > 0) {
            log.debug("이미 좌석이 생성된 회차입니다. scheduleId={}", scheduleId);
            return 0;
        }

        int total = (totalSeatCount == null || totalSeatCount <= 0) ? DEFAULT_SEAT_COUNT : totalSeatCount;
        int price = (basePrice == null || basePrice <= 0) ? DEFAULT_BASE_PRICE : basePrice;

        List<Seat> seats = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String section = String.valueOf((char) ('A' + i / SEATS_PER_ROW));
            int seatNo = (i % SEATS_PER_ROW) + 1;
            SeatGrade grade = gradeOf(i, total);

            seats.add(Seat.builder()
                    .schedule(schedule)
                    .section(section)
                    .seatNo(seatNo)
                    .grade(grade)
                    .status(SeatStatus.AVAILABLE)
                    .price(grade.applyTo(price))
                    .build());
        }
        seatRepository.saveAll(seats);
        log.info("좌석 생성 완료. scheduleId={}, count={}", scheduleId, seats.size());
        return seats.size();
    }

    private SeatGrade gradeOf(int index, int total) {
        double ratio = (double) index / total;
        if (ratio < 0.15) return SeatGrade.VIP;
        if (ratio < 0.40) return SeatGrade.R;
        if (ratio < 0.75) return SeatGrade.S;
        return SeatGrade.A;
    }
}
