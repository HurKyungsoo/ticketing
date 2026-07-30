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
     * 좌석 한 줄.
     *
     * @param rowNo      열 번호(1부터).
     * @param aisleEvery 몇 석마다 통로를 둘지. 0 이면 통로를 넣지 않는다. {@link #aisleEveryFor} 참고.
     */
    public record Row(int rowNo, List<SeatMapRow> seats, int aisleEvery) {}

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
            Map<Integer, List<SeatMapRow>> rowsByNo = entry.getValue();
            int aisleEvery = aisleEveryForSection(rowsByNo);

            List<Row> rows = rowsByNo.entrySet().stream()
                    .map(e -> new Row(e.getKey(), e.getValue(), aisleEvery))
                    .toList();
            floors.add(new Floor(entry.getKey(), "floor-" + index++, rows,
                    seatCountOf(rows), availableCountOf(rows)));
        }
        return floors;
    }

    /**
     * 대부분의 줄이 같은 길이여야 균일 구역으로 본다. 이 비율 미만이면 부채꼴로 판단한다.
     *
     * <p>"전부 같아야 한다"로 두면 안 된다 — 규모별 기본 생성은 층 좌석 수를 20 으로 나눠
     * 담기 때문에 마지막 줄만 짧은 경우가 흔하다(8줄 중 마지막이 10석). 그건 부채꼴이 아니라
     * 균일 격자이므로 통로가 그대로 들어가야 한다.
     */
    private static final double UNIFORM_ROW_SHARE = 0.8;

    /**
     * 구역 전체에 적용할 통로 간격. 줄 길이가 제각각인 부채꼴 구역은 0(통로 없음)을 준다.
     *
     * <p>통로는 "N석마다"라는 규칙으로 넣는데, 이건 줄 길이가 대체로 같을 때만 성립한다.
     * 줄이 30석~43석으로 변하는 구역에 같은 규칙을 적용하면 줄마다 통로 개수와 위치가 달라지고,
     * 줄을 가운데 정렬하기 때문에 통로가 세로로 안 맞아 지그재그로 보인다. 부채꼴은 형태
     * 자체가 실제 극장처럼 읽히므로, 어긋난 가짜 통로를 넣는 것보다 빼는 편이 낫다.
     * 실제 통로 위치는 구역을 좌/중앙/우 블록으로 쪼개야 표현할 수 있다(다음 단계).
     */
    private static int aisleEveryForSection(Map<Integer, List<SeatMapRow>> rowsByNo) {
        if (rowsByNo.isEmpty()) return 0;

        // 가장 흔한 줄 길이와 그 비중을 구한다.
        Map<Integer, Integer> countBySize = new LinkedHashMap<>();
        for (List<SeatMapRow> row : rowsByNo.values()) {
            countBySize.merge(row.size(), 1, Integer::sum);
        }
        Map.Entry<Integer, Integer> mode = countBySize.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();

        double share = (double) mode.getValue() / rowsByNo.size();
        if (share < UNIFORM_ROW_SHARE || mode.getKey() <= 0) return 0;
        return aisleEveryFor(mode.getKey());
    }

    /**
     * 통로 간격. 20석까지는 종전대로 5석마다 두고, 그보다 긴 줄은 통로가 3개가 되도록 벌린다.
     *
     * <p>5석 고정은 한 줄 20석(규모별 기본 생성)을 기준으로 만든 규칙이라 통로가 3~4개로 끝난다.
     * 그런데 콘서트홀 1층은 한 줄 41석이어서 같은 규칙이면 통로가 8개가 생기고, 통로 하나가
     * 좌석 폭의 1.25배라 좌석보다 통로가 줄 너비를 더 잡아먹는다. 실제 대형 홀도 통로를
     * 5석마다 두지 않는다.
     */
    private static int aisleEveryFor(int seatsInRow) {
        return seatsInRow <= 20 ? 5 : (int) Math.ceil(seatsInRow / 4.0);
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
