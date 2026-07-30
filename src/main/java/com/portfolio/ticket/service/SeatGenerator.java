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
 * 배치 규칙: 한 줄 20석 고정, A(6)|B(8)|C(6) 세 구역으로 나눈다. 등급은 앞에서부터
 *   상위 15% VIP / 다음 25% R / 다음 35% S / 나머지 A
 *
 * 구역(층) 구성은 공연장 규모에 따라 달라진다. 소극장을 1층 단일 구역으로,
 * 대극장을 3층 구조로 나누는 식이다. 구간 경계는 KOPIS 공통코드의
 * srchseatscale(0/100/300/500/1000/5000/10000)을 따랐다 — 임의로 정한 값이 아니다.
 * 층마다 다시 A/B/C 로 갈리므로 소극장(층 구분 없음)도 최소 세 구역은 생긴다.
 *
 * 특정 극장의 실제 좌석도가 필요하면 venue-layouts.yml 로 지정한다.
 * 여기서 만드는 건 어디까지나 규모에 맞춘 일반 구조이지 실존 극장의 좌석 배치가 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatGenerator {

    private static final int SEATS_PER_ROW = 20;
    /**
     * 규모별 기본 구조도 A/B/C 구역으로 나눈다. 실제 통로 위치는 venue-layouts.yml 의 블록으로만
     * 알 수 있지만, "정면이 몇 블록으로 갈린다"는 실제 극장 대부분에 해당하는 일반적 사실이라
     * 근사해도 무리가 없다(콘서트홀 1층 A~E, 세종 1층 A~E 등 실존 홀들도 이 형태다).
     *
     * 폭은 6|8|6 = SEATS_PER_ROW(20) 로 맞췄다. 가운데(B)를 가장 넓게 둔 건 실존 홀들의
     * 공통 패턴이다(콘서트홀 1층 C, 세종 1층 C 모두 가운데 블록이 최고가·최대폭이다).
     * 구역 경계 자체가 통로 역할을 하므로, 종전에 "5석마다" 넣던 통로는 없앤다 — 그 규칙은
     * 블록이 없던 시절 통로를 흉내 내던 임시방편이었다.
     */
    private static final int[] ZONE_WIDTHS = {6, 8, 6};
    private static final char[] ZONE_LETTERS = {'A', 'B', 'C'};
    static {
        int widthSum = 0;
        for (int w : ZONE_WIDTHS) {
            widthSum += w;
        }
        // 폭 합이 어긋나면 열 마지막 칸이 비거나 다음 줄로 넘쳐서 조용히 좌석이 깨진다.
        // 둘 중 하나만 고치는 실수를 기동 시점에 바로 잡는다.
        if (widthSum != SEATS_PER_ROW) {
            throw new ExceptionInInitializerError(
                    "ZONE_WIDTHS 합(%d)이 SEATS_PER_ROW(%d)와 다릅니다.".formatted(widthSum, SEATS_PER_ROW));
        }
    }
    private static final int DEFAULT_SEAT_COUNT = 200;
    private static final int DEFAULT_BASE_PRICE = 50_000;

    /**
     * 한 회차에 만들 좌석 수 상한.
     *
     * 공공데이터에는 대구스타디움(66,422석) 같은 대형 공연장도 들어온다. 그 수만큼 좌석 행을
     * 만들면 회차 하나가 DB 를 수만 행씩 차지하고, 화면에는 6만 칸짜리 격자가 그려진다.
     * 실제 예매 서비스도 아레나·스타디움은 개별 좌석이 아니라 구역 단위로 팔고 블록을 나눠
     * 순차 오픈한다. 여기서는 그 방식을 단순화해 상한만큼만 "오픈된 구역"으로 만든다.
     *
     * 공연장 전체 객석수는 performance.totalSeatCount 에 그대로 남으므로 정보는 잃지 않는다.
     */
    private static final int MAX_SEATS_PER_SCHEDULE = 1_500;

    private final SeatRepository seatRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final VenueLayoutProperties venueLayoutProperties;

    /** 홀 ID/공연장명이 venue-layouts.yml 의 실제 구역 구조와 일치하면 그 구조로, 아니면 기본 로직으로 생성한다. */
    @Transactional
    public int generate(Long scheduleId, String venueHallId, String venueName,
                        Integer totalSeatCount, Integer basePrice) {
        return generate(scheduleId, venueHallId, venueName, totalSeatCount, basePrice, null);
    }

    /**
     * pricesByGrade 가 있으면(KOPIS pcseguidance 파싱 결과) 등급별 실제 가격을 그대로 쓰고,
     * 없는 등급이거나 맵 자체가 null 이면 기존처럼 basePrice 에 비율을 곱한다.
     *
     * @param venueHallId KOPIS 공연장 ID(mt13id). 좌석도 매칭에 공연장명보다 우선한다.
     */
    @Transactional
    public int generate(Long scheduleId, String venueHallId, String venueName,
                        Integer totalSeatCount, Integer basePrice,
                        Map<SeatGrade, Integer> pricesByGrade) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("회차를 찾을 수 없습니다. id=" + scheduleId));

        if (seatRepository.countByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE) > 0) {
            log.debug("이미 좌석이 생성된 회차입니다. scheduleId={}", scheduleId);
            return 0;
        }

        int price = (basePrice == null || basePrice <= 0) ? DEFAULT_BASE_PRICE : basePrice;
        List<VenueLayoutProperties.SectionLayout> layout = findLayout(venueHallId, venueName);

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

    private List<VenueLayoutProperties.SectionLayout> findLayout(String venueHallId, String venueName) {
        return venueLayoutProperties.findSections(venueHallId, venueName);
    }

    private List<Seat> generateDefault(PerformanceSchedule schedule, Integer totalSeatCount, int price,
                                        Map<SeatGrade, Integer> pricesByGrade) {
        int capacity = (totalSeatCount == null || totalSeatCount <= 0) ? DEFAULT_SEAT_COUNT : totalSeatCount;
        int total = Math.min(capacity, MAX_SEATS_PER_SCHEDULE);

        if (total < capacity) {
            log.info("공연장 규모가 커서 일부 구역만 오픈한다. scheduleId={}, 공연장 객석={}, 생성={}",
                    schedule.getId(), capacity, total);
        }

        List<Seat> seats = new ArrayList<>(total);
        int index = 0;
        // 층 구성은 상한이 아니라 공연장 실제 규모로 정한다.
        // 6만석 공연장에서 1,500석만 열더라도 구조는 아레나여야 하기 때문이다.
        for (FloorSpec floor : planFloors(capacity, total)) {
            for (int i = 0; i < floor.seats(); i++) {
                SeatGrade grade = gradeOf(index, total);

                // 등급은 종전과 똑같이 "전체 좌석 중 몇 번째인지"(index)로만 정한다 — 구역을
                // 나눈다고 순회 순서를 바꾸면 같은 줄인데 먼저 도는 구역만 계속 더 좋은 등급을
                // 받는 부작용이 생긴다. 열 위치(col)는 순전히 어느 구역·구역 내 몇 번째 좌석인지
                // 표시에만 쓴다.
                int col = i % SEATS_PER_ROW;
                int rowNo = (i / SEATS_PER_ROW) + 1;
                Zone zone = zoneOf(col);
                String section = floor.name().isEmpty()
                        ? String.valueOf(zone.letter())
                        : floor.name() + " " + zone.letter();

                // 구역 경계가 곧 통로다. 줄 끝에는 두지 않는다 — 여백만 생기고 구분 효과가
                // 없다. 층 좌석 수가 20 으로 나누어떨어지지 않으면 마지막 줄이 짧으므로,
                // 20번째인지가 아니라 "이 줄의 마지막 좌석인지"로 판단해야 한다.
                boolean lastInRow = col == SEATS_PER_ROW - 1 || i == floor.seats() - 1;
                boolean aisle = zone.isLastColumn(col) && !lastInRow;

                seats.add(Seat.builder()
                        .schedule(schedule)
                        .section(section)
                        .rowNo(rowNo)
                        .seatNo(zone.localSeatNo(col))
                        .aisleAfter(aisle)
                        .grade(grade)
                        .status(SeatStatus.AVAILABLE)
                        .price(resolvePrice(grade, price, pricesByGrade))
                        .build());
                index++;
            }
        }
        return seats;
    }

    /** 층 이름과 그 층에 배정된 좌석 수. */
    private record FloorSpec(String name, int seats) {}

    /** 한 줄 안에서 구역이 차지하는 칸 범위. 알파벳·시작 칸·폭만 있으면 나머지는 계산으로 나온다. */
    private record Zone(char letter, int startCol, int width) {
        int localSeatNo(int col) {
            return col - startCol + 1;
        }

        boolean isLastColumn(int col) {
            return col == startCol + width - 1;
        }
    }

    private static final Zone[] ZONES = buildZones();

    private static Zone[] buildZones() {
        Zone[] zones = new Zone[ZONE_WIDTHS.length];
        int col = 0;
        for (int i = 0; i < ZONE_WIDTHS.length; i++) {
            zones[i] = new Zone(ZONE_LETTERS[i], col, ZONE_WIDTHS[i]);
            col += ZONE_WIDTHS[i];
        }
        return zones;
    }

    /** 줄 안에서 이 칸(0부터)이 어느 구역에 속하는지. */
    private static Zone zoneOf(int col) {
        for (Zone zone : ZONES) {
            if (col < zone.startCol() + zone.width()) {
                return zone;
            }
        }
        throw new IllegalStateException("좌석 칸이 구역 폭 합계를 벗어났습니다. col=" + col);
    }

    /**
     * 공연장 규모(capacity)로 층 구성을 정하고, 실제로 만들 좌석 수(total)를 층별 비율로 나눈다.
     * 나누어떨어지지 않는 잔여분은 1층에 몰아준다.
     */
    private List<FloorSpec> planFloors(int capacity, int total) {
        String[] names;
        double[] ratios;

        if (capacity <= 300) {            // 소극장
            names = new String[]{""};
            ratios = new double[]{1.0};
        } else if (capacity <= 1000) {    // 중극장
            names = new String[]{"1층", "2층"};
            ratios = new double[]{0.70, 0.30};
        } else if (capacity <= 5000) {    // 대극장
            names = new String[]{"1층", "2층", "3층"};
            ratios = new double[]{0.55, 0.30, 0.15};
        } else {                          // 아레나 / 스타디움
            names = new String[]{"플로어", "1층", "2층"};
            ratios = new double[]{0.40, 0.35, 0.25};
        }

        List<FloorSpec> floors = new ArrayList<>(names.length);
        int assigned = 0;
        for (int i = 1; i < names.length; i++) {
            int seats = (int) Math.round(total * ratios[i]);
            floors.add(new FloorSpec(names[i], seats));
            assigned += seats;
        }
        // 첫 층을 마지막에 계산해 합이 total 과 정확히 맞도록 한다.
        floors.add(0, new FloorSpec(names[0], total - assigned));
        return floors;
    }

    /**
     * 구역마다 줄(1열, 2열...) x 좌석 구조로 만든다. 등급은 구역 단위로 고정.
     *
     * <p>줄 수에 상한이 없고, {@code seatCounts} 로 줄마다 좌석 수를 다르게 줄 수 있다.
     * 실제 극장은 뒤로 갈수록 넓어지는 부채꼴이라 직사각형으로는 형태가 안 나온다.
     * 화면은 줄을 가운데 정렬하므로(app.css 의 {@code .row { margin-inline: auto }})
     * 줄 길이만 달라지면 부채꼴이 그대로 그려진다.
     */
    private List<Seat> generateFromLayout(PerformanceSchedule schedule,
                                          List<VenueLayoutProperties.SectionLayout> layout, int price,
                                          Map<SeatGrade, Integer> pricesByGrade) {
        List<Seat> seats = new ArrayList<>();
        for (VenueLayoutProperties.SectionLayout section : layout) {
            // 줄마다 좌석 수가 다를 수 있다 (seatCounts). 직사각형 구역은 같은 값이 반복된다.
            List<Integer> rowSizes = section.rowSizes();
            for (int row = 0; row < rowSizes.size(); row++) {
                int rowSize = rowSizes.get(row);
                // 중앙 블록은 폭이 고정이고 좌우가 부채꼴 변화를 흡수한다. 나누어떨어지지
                // 않으면 왼쪽에 한 석을 더 준다(어느 쪽이든 한 석 차이라 임의로 정한다).
                int sides = section.hasBlocks() ? rowSize - section.getCenterSeats() : 0;
                int leftEnd = (sides + 1) / 2;
                int centerEnd = rowSize - sides / 2;

                for (int seatNo = 1; seatNo <= rowSize; seatNo++) {
                    boolean inCenter = !section.hasBlocks() || (seatNo > leftEnd && seatNo <= centerEnd);
                    SeatGrade grade = inCenter ? section.getGrade() : section.getSideGrade();
                    // 블록 경계 좌석 뒤에만 통로. 줄 끝에는 두지 않는다(빈 여백만 생긴다).
                    boolean aisle = section.hasBlocks() && (seatNo == leftEnd || seatNo == centerEnd)
                            && seatNo < rowSize;

                    seats.add(Seat.builder()
                            .schedule(schedule)
                            .section(section.getName())
                            .rowNo(row + 1)
                            .seatNo(seatNo)
                            .aisleAfter(aisle)
                            .grade(grade)
                            .status(SeatStatus.AVAILABLE)
                            .price(resolvePrice(grade, price, pricesByGrade))
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
