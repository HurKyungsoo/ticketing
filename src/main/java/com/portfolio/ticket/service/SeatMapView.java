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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * "1층 A" 처럼 끝에 구역 알파벳이 붙은 이름에서 tier/column 을 뽑아낸다.
     *
     * <p>좌석도(venue-layouts.yml)가 없는 홀(SeatGenerator.generateDefault 가 만든 기본 구조)은
     * tier/column 을 좌석도로 넘길 방법이 없다 — 좌석 테이블에도 안 넣는다는 원칙이 여기도
     * 그대로 적용된다. 대신 구역 이름 자체에 이미 순서 정보가 있으므로("1층 A" 다음이
     * "1층 B") 조회 시점에 이름을 갈라서 같은 결론을 낸다. 두 자리 모두 없는 구역(예시극장의
     * "1층뒤" 등 순수 한글 구역명)은 매치되지 않아 종전과 같이 tier="" 로 남는다.
     */
    private static final Pattern ZONE_SUFFIX = Pattern.compile("^(.*?)\\s?([A-Z])$");

    /**
     * 좌석 한 줄. 통로 위치는 좌석마다 {@code aisleAfter} 로 들어 있어서 여기서 계산할 게 없다.
     *
     * @param rowNo 열 번호(1부터).
     */
    public record Row(int rowNo, List<SeatMapRow> seats) {}

    /**
     * 한 구역(층) 블록.
     *
     * @param name     구역 이름("1층 A", "합창석"). 좌석도가 없는 기본 생성 홀도 A/B/C 로
     *                 갈리므로 실질적으로 항상 값이 있다 — 이미 생성된 옛 데이터에만 빈
     *                 문자열이 남아 있을 수 있다.
     * @param anchorId  구역 바로가기 링크용 id. 구역 이름에 한글·숫자가 섞여 그대로는 못 쓴다.
     * @param shortName 층 이름을 뗀 구역 이름("1층 A" → "A"). 층 이름은 밴드 머릿글이 한 번만
     *                  쓰므로, 구역마다 "1층" 을 되풀이하면 같은 정보가 다섯 번 반복된다.
     *                  뗄 게 없으면(구역명이 곧 층 이름) 빈 문자열이고, 그때 화면은 구역
     *                  이름을 아예 안 그린다 — 머릿글과 똑같은 글자가 두 번 나오게 된다.
     */
    public record Floor(String name, String anchorId, String shortName, List<Row> rows,
                        int seatCount, int availableCount) {

        /**
         * 이 구역에서 가장 긴 줄의 좌석 수. 부채꼴 구역(줄마다 좌석 수가 다름)의 화면 CSS
         * (--max-n)가 줄번호 라벨을 한 줄로 정렬하는 데 쓴다 — 자세한 이유는 app.css
         * ".rowlabel + .seat" 규칙 주석 참고.
         */
        public int maxRowSize() {
            return rows.stream().mapToInt(r -> r.seats().size()).max().orElse(0);
        }
    }

    /**
     * 한 층(밴드). 가로로 나란히 놓이는 열들의 묶음이고, 열마다 구역이 위에서 아래로 쌓인다.
     *
     * <p>세종문화회관 대극장 1층처럼 한 층이 A~E 다섯 구역으로 갈리는 홀을 표현한다.
     * 좌석도에 {@code tier} 를 적지 않은 구역들은 열이 하나뿐인 밴드로 묶여서 종전과 같다.
     *
     * @param name 층 이름("1층", "합창석"). 좌석도의 {@code tier} 값이거나 구역명에서
     *             떼어낸 접두사다. 층 바로가기가 이 단위로 묶이므로 화면에 나간다.
     */
    public record Band(String name, List<List<Floor>> columns) {

        /** 열 구분 없이 이 층의 구역 전부. 층 단위 집계와 바로가기 목적지에 쓴다. */
        public List<Floor> floors() {
            return columns.stream().flatMap(List::stream).toList();
        }

        public int seatCount() {
            return floors().stream().mapToInt(Floor::seatCount).sum();
        }

        public int availableCount() {
            return floors().stream().mapToInt(Floor::availableCount).sum();
        }

        /**
         * 층 바로가기 목적지. 층 자체에는 id 가 없고 구역에만 있으므로 첫 구역으로 보낸다.
         * 구역은 화면에 그리는 순서대로 들어 있어서 첫 구역이 곧 그 층의 시작이다.
         */
        public String anchorId() {
            List<Floor> floors = floors();
            return floors.isEmpty() ? "" : floors.get(0).anchorId();
        }

        /**
         * 층 머릿글 자체의 id. 층 바로가기는 여기로 보낸다 — 첫 구역({@link #anchorId()})으로
         * 보내면 머릿글을 지나쳐 멈춰서, 방금 누른 층 이름이 화면 위로 밀려나 안 보인다.
         */
        public String headerAnchorId() {
            String first = anchorId();
            return first.isEmpty() ? "" : first + "-head";
        }

        /**
         * 층 바로가기가 실제로 걸 목적지. 머릿글은 층 이름({@code tier})을 얻은 밴드에만
         * 그리므로, 못 얻은 밴드는 머릿글이 없어 첫 구역으로 보내야 한다(종전 동작).
         */
        public String jumpTargetId() {
            return name.isBlank() ? anchorId() : headerAnchorId();
        }

        /**
         * 화면에 쓸 이름. {@code tier} 를 못 얻은 홀(구역명이 "1층 A" 꼴이 아니어서 접두사를
         * 떼지 못한 경우)은 이름이 빈 문자열이라, 그때는 첫 구역 이름을 그대로 쓴다.
         */
        public String displayName() {
            if (!name.isBlank()) {
                return name;
            }
            List<Floor> floors = floors();
            return floors.isEmpty() ? "" : floors.get(0).name();
        }
    }

    /**
     * 무대를 기준으로 놓인 좌석도. 화면은 무대 뒤 밴드 → 무대 → 정면 밴드 순으로 그린다.
     *
     * <pre>
     *        [ rear 밴드 ]                무대 뒤 (합창석 등)
     *        [ STAGE ]
     *   [ 1층 A | B | C | D | E ]         정면 밴드 (층마다 하나)
     *   [ 2층 A | B | ...        ]
     * </pre>
     */
    public record Arena(List<Band> rear, List<Band> front) {

        public List<Band> bands() {
            List<Band> bands = new ArrayList<>(rear);
            bands.addAll(front);
            return bands;
        }

        /**
         * 한 밴드라도 열이 여러 개인지. 그러면 열마다 하나씩 있는 행 라벨을 스크롤에
         * 고정할 수 없다 — sticky 는 스크롤 컨테이너 왼쪽 기준이라 전부 왼쪽 끝에 겹쳐 붙는다.
         */
        public boolean hasSides() {
            return bands().stream().anyMatch(band -> band.columns().size() > 1);
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

        /*
           밴드(무대앞뒤 + 층) → 열 → 구역.
           밴드는 LinkedHashMap 이라 처음 나온 순서(= 좌석도 선언 순서)가 유지되고,
           열은 TreeMap 이라 column 값 오름차순 = 왼쪽부터다. 값이 연속일 필요는 없다.
        */
        Map<SectionPosition, Map<String, Map<Integer, List<Floor>>>> bands = new EnumMap<>(SectionPosition.class);
        for (SectionPosition position : SectionPosition.values()) {
            bands.put(position, new LinkedHashMap<>());
        }

        int index = 0;
        for (String name : sectionNames) {
            SectionLayout section = layoutBySection.get(name);
            SectionPosition position;
            String tier;
            int column;
            if (section != null) {
                position = section.getPosition();
                tier = section.getTier();
                column = section.getColumn();
            } else {
                position = SectionPosition.FRONT;
                Matcher m = ZONE_SUFFIX.matcher(name);
                if (m.matches()) {
                    tier = m.group(1);
                    column = m.group(2).charAt(0) - 'A' + 1;
                } else {
                    tier = "";
                    column = 0;
                }
            }

            bands.get(position)
                    .computeIfAbsent(tier, k -> new TreeMap<>())
                    .computeIfAbsent(column, k -> new ArrayList<>())
                    .add(floorOf(name, "floor-" + index++, tier, bySection.get(name), position));
        }

        return new Arena(bandsOf(bands.get(SectionPosition.REAR)), bandsOf(bands.get(SectionPosition.FRONT)));
    }

    private List<Band> bandsOf(Map<String, Map<Integer, List<Floor>>> byTier) {
        // 키(tier)가 곧 층 이름이다. 종전엔 그룹핑에만 쓰고 버렸는데, 층 바로가기가 이 단위라 같이 넘긴다.
        return byTier.entrySet().stream()
                .map(entry -> new Band(entry.getKey(), List.copyOf(entry.getValue().values())))
                .toList();
    }

    private Floor floorOf(String name, String anchorId, String tier,
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

        return new Floor(name, anchorId, shortNameOf(name, tier),
                List.copyOf(rows), seatCountOf(rows), availableCountOf(rows));
    }

    /**
     * 구역 이름에서 층 이름을 뗀다("1층 A" + tier "1층" → "A"). 층은 밴드 머릿글이 한 번만
     * 쓰므로 구역마다 되풀이할 필요가 없다.
     *
     * <p>뗀 결과가 비면 빈 문자열을 준다 — 구역 이름이 곧 층 이름인 경우(열이 하나뿐인 밴드)라
     * 화면에 또 그리면 머릿글과 같은 글자가 두 번 나온다. tier 를 못 얻은 홀은 접두사가 없으니
     * 이름을 그대로 쓴다(그 홀은 밴드 머릿글도 첫 구역 이름을 쓰므로 중복되지 않는다).
     */
    private static String shortNameOf(String name, String tier) {
        if (tier == null || tier.isBlank() || !name.startsWith(tier)) {
            return name;
        }
        return name.substring(tier.length()).trim();
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
