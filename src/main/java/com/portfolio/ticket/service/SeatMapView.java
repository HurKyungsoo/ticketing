package com.portfolio.ticket.service;

import com.portfolio.ticket.mapper.dto.SeatMapRow;
import com.portfolio.ticket.service.VenueLayoutProperties.SectionLayout;
import com.portfolio.ticket.service.VenueLayoutProperties.SectionPosition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 좌석 배치도를 화면 구조(무대 기준 배치 → 구역 → 줄 → 좌석)로 접는다.
 * 컨트롤러는 조회/전달만 하고 조립은 여기서 한다 (CLAUDE.md: 컨트롤러에 로직 금지).
 *
 * <p>대형 홀에서는 구역을 나눠 보여주지 않으면 화면을 읽을 수 없다. 2,481석짜리 예술의전당
 * 콘서트홀은 줄이 63개인데, 구역 구분 없이 한 덩어리로 이어지면 지금 몇 층을 보고 있는지
 * 알 수 없다. 구역 이름은 블록 제목으로 한 번만 쓰고, 줄 라벨에는 열 번호만 쓴다.
 *
 * <p><b>왜 무대 기준으로 배치하는가</b> — 구역을 위에서 아래로 쌓기만 하면 무대를 감싸는 홀의
 * 구조가 표현되지 않는다. 무대 뒤 합창석이 3층보다 아래, 즉 무대에서 가장 먼 자리에 그려지고
 * (구역명 사전순이라 'ㅎ'이 마지막), 빈야드형 홀의 좌우 구역은 정면 구역과 구분되지 않는다.
 * 구역의 위치는 좌석도(venue-layouts.yml)가 아는 사실이므로 조회 시점에 붙인다.
 */
@Service
@RequiredArgsConstructor
public class SeatMapView {

    private final VenueLayoutProperties venueLayoutProperties;

    /**
     * 좌석 한 줄. 통로 위치는 좌석마다 {@code aisleAfter} 로 들어 있어서 여기서 계산할 게 없다.
     *
     * @param rowNo 열 번호(1부터).
     */
    public record Row(int rowNo, List<SeatMapRow> seats) {}

    /**
     * 한 구역(층) 블록.
     *
     * @param name     구역 이름("1층", "합창석"). 층 구분이 없는 소극장은 빈 문자열이다.
     * @param anchorId 구역 바로가기 링크용 id. 구역 이름에 한글·숫자가 섞여 그대로는 못 쓴다.
     */
    public record Floor(String name, String anchorId, List<Row> rows,
                        int seatCount, int availableCount) {}

    /**
     * 무대를 기준으로 놓인 좌석도. 화면은 이걸 두 줄로 그린다.
     *
     * <pre>
     *          [ rear ]        무대 뒤 (합창석 등)
     *          [ STAGE ]
     *   [ left ] [ center ] [ right ]
     * </pre>
     *
     * <p>좌석도가 지정되지 않은 홀은 모든 구역이 {@code center} 로 들어와서 종전과 똑같이
     * 위에서 아래로 쌓인다.
     */
    public record Arena(List<Floor> rear, List<Floor> left, List<Floor> center, List<Floor> right) {

        /** 층 바로가기(floornav)용 전체 목록. 무대에 가까운 쪽부터 나열한다. */
        public List<Floor> all() {
            List<Floor> all = new ArrayList<>(rear);
            all.addAll(left);
            all.addAll(center);
            all.addAll(right);
            return all;
        }

        /**
         * 무대 정면에 좌우 구역이 있는지. 있으면 화면이 세 열로 나뉘어서, 열마다 하나씩
         * 있는 행 라벨을 스크롤에 고정할 수 없다(왼쪽 끝에 겹쳐 붙는다).
         */
        public boolean hasSides() {
            return !left.isEmpty() || !right.isEmpty();
        }
    }

    /**
     * 좌석 목록을 무대 기준 구역 블록으로 묶는다.
     *
     * <p>구역 순서는 좌석도에 적은 순서를 따르고, 좌석도에 없는 구역은 그 뒤에 좌석 조회 정렬
     * ({@code ORDER BY section, row_no, seat_no}) 순서대로 남는다. 줄과 좌석 순서는 언제나 쿼리
     * 정렬 그대로다.
     *
     * @param hallId    KOPIS 공연장 ID(mt13id). 좌석도 매칭에 공연장명보다 우선한다.
     * @param venueName 공연장명. 홀 ID 가 없는 소스(문화정보/표준데이터)용 대체 키.
     */
    public Arena arenaOf(List<SeatMapRow> seats, String hallId, String venueName) {
        List<SectionLayout> layout = venueLayoutProperties.findSections(hallId, venueName);

        // 구역 → 열 번호 → 좌석. 둘 다 LinkedHashMap 이라 쿼리 정렬이 그대로 유지된다.
        Map<String, Map<Integer, List<SeatMapRow>>> bySection = new LinkedHashMap<>();
        for (SeatMapRow seat : seats) {
            bySection.computeIfAbsent(seat.getSection(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(seat.getRowNo(), k -> new ArrayList<>())
                    .add(seat);
        }

        Map<String, SectionLayout> layoutBySection = new HashMap<>();
        Map<String, Integer> orderBySection = new HashMap<>();
        if (layout != null) {
            for (int i = 0; i < layout.size(); i++) {
                layoutBySection.put(layout.get(i).getName(), layout.get(i));
                orderBySection.put(layout.get(i).getName(), i);
            }
        }

        // 좌석도에 없는 구역은 맨 뒤로. List.sort 는 안정 정렬이라 그들끼리는 쿼리 순서가 남는다.
        List<String> sectionNames = new ArrayList<>(bySection.keySet());
        sectionNames.sort(Comparator.comparingInt(
                name -> orderBySection.getOrDefault(name, Integer.MAX_VALUE)));

        Map<SectionPosition, List<Floor>> byPosition = new EnumMap<>(SectionPosition.class);
        for (SectionPosition position : SectionPosition.values()) {
            byPosition.put(position, new ArrayList<>());
        }

        int index = 0;
        for (String name : sectionNames) {
            SectionLayout section = layoutBySection.get(name);
            SectionPosition position = (section != null) ? section.getPosition() : SectionPosition.CENTER;
            byPosition.get(position).add(floorOf(name, "floor-" + index++, bySection.get(name), position));
        }

        return new Arena(byPosition.get(SectionPosition.REAR),
                byPosition.get(SectionPosition.LEFT),
                byPosition.get(SectionPosition.CENTER),
                byPosition.get(SectionPosition.RIGHT));
    }

    private Floor floorOf(String name, String anchorId,
                          Map<Integer, List<SeatMapRow>> rowsByNo, SectionPosition position) {
        List<Row> rows = new ArrayList<>(rowsByNo.size());
        rowsByNo.forEach((rowNo, seats) -> rows.add(new Row(rowNo, seats)));

        /*
           무대 뒤 구역은 줄 순서를 뒤집는다. 1열이 무대에서 가장 가까운 줄인데, 이 구역은
           화면에서 무대 위쪽에 놓이므로 무대에 가까운 줄이 블록의 아래쪽(무대와 맞닿는 쪽)에
           와야 한다. 그대로 그리면 무대에서 가장 먼 줄이 무대에 붙어 보인다.
        */
        if (position == SectionPosition.REAR) {
            rows.sort(Comparator.comparingInt(Row::rowNo).reversed());
        }

        return new Floor(name, anchorId, List.copyOf(rows), seatCountOf(rows), availableCountOf(rows));
    }

    private static int seatCountOf(List<Row> rows) {
        return rows.stream().mapToInt(r -> r.seats().size()).sum();
    }

    private static int availableCountOf(List<Row> rows) {
        return (int) rows.stream().flatMap(r -> r.seats().stream())
                .filter(s -> "AVAILABLE".equals(s.getStatus()))
                .count();
    }
}
