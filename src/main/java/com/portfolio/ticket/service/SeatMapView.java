package com.portfolio.ticket.service;

import com.portfolio.ticket.mapper.dto.SeatMapRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 좌석 배치도를 화면 구조(구역 → 줄 → 좌석)로 접는다.
 * 컨트롤러는 조회/전달만 하고 조립은 여기서 한다 (CLAUDE.md: 컨트롤러에 로직 금지).
 *
 * <p>대형 홀에서는 구역을 나눠 보여주지 않으면 화면을 읽을 수 없다. 2,501석짜리 예술의전당
 * 콘서트홀은 줄이 65개인데, 구역 구분 없이 한 덩어리로 이어지면 지금 몇 층을 보고 있는지
 * 알 수 없다. 구역 이름은 블록 제목으로 한 번만 쓰고, 줄 라벨에는 열 번호만 쓴다.
 */
public final class SeatMapView {

    private SeatMapView() {
    }

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
     * 좌석 목록을 구역 블록으로 묶는다. 정렬은 쿼리(ORDER BY section, row_no, seat_no)를
     * 그대로 따르므로 구역 블록 순서와 줄 순서가 곧 화면 표시 순서다.
     */
    public static List<Floor> floorsOf(List<SeatMapRow> seats) {
        // 구역 → 열 번호 → 좌석. 둘 다 LinkedHashMap 이라 쿼리 정렬이 그대로 유지된다.
        Map<String, Map<Integer, List<SeatMapRow>>> bySection = new LinkedHashMap<>();
        for (SeatMapRow seat : seats) {
            bySection.computeIfAbsent(seat.getSection(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(seat.getRowNo(), k -> new ArrayList<>())
                    .add(seat);
        }

        List<Floor> floors = new ArrayList<>(bySection.size());
        int index = 0;
        for (Map.Entry<String, Map<Integer, List<SeatMapRow>>> entry : bySection.entrySet()) {
            List<Row> rows = entry.getValue().entrySet().stream()
                    .map(e -> new Row(e.getKey(), e.getValue()))
                    .toList();
            floors.add(new Floor(entry.getKey(), "floor-" + index++, rows,
                    seatCountOf(rows), availableCountOf(rows)));
        }
        return floors;
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
