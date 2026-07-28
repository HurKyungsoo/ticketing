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
import java.util.Map;

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
    private final VenueLayoutProperties venueLayoutProperties;

    /** venueName 이 venue-layouts.yml 의 실제 구역 구조와 일치하면 그 구조로, 아니면 기본 로직으로 생성한다. */
    @Transactional
    public int generate(Long scheduleId, String venueName, Integer totalSeatCount, Integer basePrice) {
        return generate(scheduleId, venueName, totalSeatCount, basePrice, null);
    }

    /**
     * pricesByGrade 가 있으면(KOPIS pcseguidance 파싱 결과) 등급별 실제 가격을 그대로 쓰고,
     * 없는 등급이거나 맵 자체가 null 이면 기존처럼 basePrice 에 비율을 곱한다.
     */
    @Transactional
    public int generate(Long scheduleId, String venueName, Integer totalSeatCount, Integer basePrice,
                         Map<SeatGrade, Integer> pricesByGrade) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("회차를 찾을 수 없습니다. id=" + scheduleId));

        if (seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE) > 0) {
            log.debug("이미 좌석이 생성된 회차입니다. scheduleId={}", scheduleId);
            return 0;
        }

        int price = (basePrice == null || basePrice <= 0) ? DEFAULT_BASE_PRICE : basePrice;
        List<VenueLayoutProperties.SectionLayout> layout = findLayout(venueName);

        List<Seat> seats = (layout != null)
                ? generateFromLayout(schedule, layout, price, pricesByGrade)
                : generateDefault(schedule, totalSeatCount, price, pricesByGrade);

        seatRepository.saveAll(seats);

        if (seats.size() != schedule.getTotalSeats()) {
            schedule.syncActualSeatCount(seats.size());
        }

        log.info("좌석 생성 완료. scheduleId={}, count={}, layout={}", scheduleId, seats.size(), layout != null);
        return seats.size();
    }

    private List<VenueLayoutProperties.SectionLayout> findLayout(String venueName) {
        return venueLayoutProperties.findSections(venueName);
    }

    private List<Seat> generateDefault(PerformanceSchedule schedule, Integer totalSeatCount, int price,
                                        Map<SeatGrade, Integer> pricesByGrade) {
        int total = (totalSeatCount == null || totalSeatCount <= 0) ? DEFAULT_SEAT_COUNT : totalSeatCount;

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
                    .price(resolvePrice(grade, price, pricesByGrade))
                    .build());
        }
        return seats;
    }

    /** 구역마다 행(A, B, C...) x 열 구조로 좌석을 만든다. 등급은 구역 단위로 고정. */
    private List<Seat> generateFromLayout(PerformanceSchedule schedule,
                                          List<VenueLayoutProperties.SectionLayout> layout, int price,
                                          Map<SeatGrade, Integer> pricesByGrade) {
        List<Seat> seats = new ArrayList<>();
        for (VenueLayoutProperties.SectionLayout section : layout) {
            for (int row = 0; row < section.getRows(); row++) {
                String sectionLabel = section.getName() + (char) ('A' + row);
                for (int seatNo = 1; seatNo <= section.getSeatsPerRow(); seatNo++) {
                    seats.add(Seat.builder()
                            .schedule(schedule)
                            .section(sectionLabel)
                            .seatNo(seatNo)
                            .grade(section.getGrade())
                            .status(SeatStatus.AVAILABLE)
                            .price(resolvePrice(section.getGrade(), price, pricesByGrade))
                            .build());
                }
            }
        }
        return seats;
    }

    private int resolvePrice(SeatGrade grade, int fallbackBasePrice, Map<SeatGrade, Integer> pricesByGrade) {
        if (pricesByGrade != null && pricesByGrade.containsKey(grade)) {
            return pricesByGrade.get(grade);
        }
        return grade.applyTo(fallbackBasePrice);
    }

    private SeatGrade gradeOf(int index, int total) {
        double ratio = (double) index / total;
        if (ratio < 0.15) return SeatGrade.VIP;
        if (ratio < 0.40) return SeatGrade.R;
        if (ratio < 0.75) return SeatGrade.S;
        return SeatGrade.A;
    }
}
