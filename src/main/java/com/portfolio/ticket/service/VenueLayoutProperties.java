package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.SeatGrade;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 공연장 -> 실제 구역 구조(venue-layouts.yml). SeatGenerator 가 참조한다.
 * 일치하는 항목이 없으면 기본 좌석 생성 로직으로 대체된다.
 *
 * 매칭 키는 <b>홀 ID(hallId, KOPIS mt13id)를 우선</b>하고, 없을 때만 공연장명으로 찾는다.
 * 공연장명은 시설 이름이라 다관 시설을 구분하지 못한다 — 실제로 "예술의전당 [서울]" 하나에
 * 354석 홀(FC000001-02)과 2,505석 홀(FC000001-03)이 함께 있어서, 이름으로 매칭하면
 * 한쪽은 반드시 틀린 배치를 받는다. 홀 ID 는 코드라 공연장 표기가 바뀌어도 깨지지 않는다.
 *
 * 이름 매칭을 남겨두는 이유는 KOPIS 가 아닌 소스(문화정보/표준데이터)에는 홀 ID 자체가
 * 없기 때문이다. 그런 공연은 종전대로 이름으로만 찾는다.
 *
 * venueName 을 Map 키로 쓰면 한글 키 바인딩 시 Spring 이 중첩 제네릭 타입을
 * 못 찾는 경우가 있어(ConverterNotFoundException), List + 필드 구조로 우회했다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "venue")
public class VenueLayoutProperties {

    private List<VenueLayout> layouts = new ArrayList<>();

    /**
     * 구역이 무대를 기준으로 어디에 놓이는지. <b>화면 배치에만 쓴다</b> — 좌석 테이블에는 없다.
     *
     * <p>좌석의 정체성은 "구역 N열 M번" 이고 위치는 그 구역이 홀에서 어디냐는 별개의 사실이다.
     * 좌석마다 들고 있어도 구역 단위로 항상 같은 값이라 중복이고, 위치를 고치려면 좌석 수만
     * 개를 다시 만들어야 한다. 좌석도(venue-layouts.yml)가 아는 사실이므로 조회 시점에 붙인다.
     */
    public enum SectionPosition {
        /** 무대 정면. 기본값이고, 좌석도가 없는 홀의 모든 구역이 여기 들어간다. */
        CENTER,
        /** 무대 정면에서 왼쪽. 빈야드형처럼 객석이 무대를 감싸는 홀에서 쓴다. */
        LEFT,
        /** 무대 정면에서 오른쪽. */
        RIGHT,
        /** 무대 뒤(합창석 등). 화면에서는 무대 위쪽에 그린다. */
        REAR
    }

    /**
     * 설정 오류는 기동할 때 바로 터뜨린다.
     *
     * <p>좌석도는 외부 API 응답이 아니라 우리가 직접 쓰는 파일이다(그쪽은 CLAUDE.md 대로 건별
     * 스킵한다). 오타를 조용히 넘기면 엉뚱한 좌석이 수만 개 만들어진 뒤에야 알게 되고,
     * 되돌리려면 회차 전체를 재생성해야 한다.
     */
    @PostConstruct
    void validate() {
        for (VenueLayout layout : layouts) {
            String where = layout.getHallId() != null ? layout.getHallId() : layout.getVenueName();
            if (layout.getSections() == null || layout.getSections().isEmpty()) {
                throw new IllegalStateException("좌석도에 sections 가 없습니다. venue-layouts.yml: " + where);
            }
            for (SectionLayout section : layout.getSections()) {
                validateSection(where, section);
            }
        }
    }

    private void validateSection(String where, SectionLayout section) {
        String at = where + " / " + section.getName();
        if (section.getName() == null || section.getName().isBlank()) {
            throw new IllegalStateException("구역 이름이 비어 있습니다. venue-layouts.yml: " + where);
        }
        if (section.getGrade() == null) {
            throw new IllegalStateException("구역 등급(grade)이 없습니다. venue-layouts.yml: " + at);
        }
        // 값 없이 "position:" 만 적으면 null 이 된다. 기본값(CENTER)은 생략했을 때만 남는다.
        if (section.getPosition() == null) {
            throw new IllegalStateException(
                    "구역 위치(position)가 비어 있습니다. 무대 정면이면 줄 자체를 생략하세요. venue-layouts.yml: " + at);
        }

        boolean rectangle = section.getRows() > 0 && section.getSeatsPerRow() > 0;
        if (section.hasSeatCounts() == rectangle) {
            throw new IllegalStateException(
                    "seatCounts 와 rows/seatsPerRow 중 정확히 하나만 적어야 합니다. venue-layouts.yml: " + at);
        }
        if (section.hasSeatCounts() && section.getSeatCounts().stream().anyMatch(n -> n == null || n <= 0)) {
            throw new IllegalStateException("seatCounts 에 0 이하 값이 있습니다. venue-layouts.yml: " + at);
        }

        if (section.hasBlocks()) {
            if (section.getSideGrade() == null) {
                throw new IllegalStateException("centerSeats 를 쓰면 sideGrade 가 필요합니다. venue-layouts.yml: " + at);
            }
            // 가장 짧은 줄에도 좌우 블록이 최소 1석씩 남아야 한다. 안 그러면 그 줄만 통로가 사라진다.
            int shortest = section.rowSizes().stream().mapToInt(Integer::intValue).min().orElse(0);
            if (section.getCenterSeats() > shortest - 2) {
                throw new IllegalStateException(("centerSeats(%d)가 가장 짧은 줄(%d석)에 비해 큽니다 — "
                        + "좌우 블록이 최소 1석씩 남아야 합니다. venue-layouts.yml: %s")
                        .formatted(section.getCenterSeats(), shortest, at));
            }

            /*
               좌우 블록 폭이 같아야 통로가 세로로 정렬된다. (줄 길이 - 중앙)이 짝수여야 하므로
               줄 길이와 centerSeats 의 홀짝이 같아야 한다. 어긋나면 그 줄만 좌 6 | 중앙 20 | 우 5
               처럼 비대칭이 되고, 줄이 가운데 정렬이라 통로가 반 칸 밀려 보인다.

               실제 대칭 홀도 줄이 2석씩(양쪽 1석씩) 늘어나기 때문에 한 구역의 줄 길이는
               홀짝이 일정하다 — 이 제약은 우리 렌더러 사정이 아니라 극장 구조 그대로다.
            */
            boolean centerIsOdd = section.getCenterSeats() % 2 != 0;
            List<Integer> mismatched = section.rowSizes().stream()
                    .filter(n -> (n % 2 != 0) != centerIsOdd)
                    .distinct()
                    .toList();
            if (!mismatched.isEmpty()) {
                throw new IllegalStateException(("줄 길이와 centerSeats(%d) 의 홀짝이 달라 좌우 블록이 "
                        + "비대칭이 됩니다. 어긋난 줄 길이=%s. 대칭 홀은 줄이 2석씩 늘어나므로 한 구역의 "
                        + "줄 길이는 홀짝이 같아야 합니다. venue-layouts.yml: %s")
                        .formatted(section.getCenterSeats(), mismatched, at));
            }
        }
    }

    /**
     * 홀 ID 로 먼저 찾고, 못 찾으면 공연장명으로 찾는다.
     * 홀 ID 항목이 하나라도 걸리면 이름 매칭은 시도하지 않는다 — 홀 단위가 항상 더 정확하다.
     */
    public List<SectionLayout> findSections(String hallId, String venueName) {
        List<SectionLayout> byHall = findBy(hallId, VenueLayout::getHallId);
        return byHall != null ? byHall : findBy(venueName, VenueLayout::getVenueName);
    }

    private List<SectionLayout> findBy(String key, Function<VenueLayout, String> extractor) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();

        return layouts.stream()
                .filter(v -> {
                    String value = extractor.apply(v);
                    return value != null && trimmed.equals(value.trim());
                })
                .map(VenueLayout::getSections)
                .findFirst()
                .orElse(null);
    }

    @Getter
    @Setter
    public static class VenueLayout {
        /** KOPIS 공연장 ID(mt13id). 예: FC000001-03. 지정하면 이름보다 우선한다. */
        private String hallId;
        private String venueName;
        private List<SectionLayout> sections;
    }

    @Getter
    @Setter
    public static class SectionLayout {
        private String name;

        /** 직사각형 구역용. seatCounts 를 쓰면 무시된다. */
        private int rows;
        private int seatsPerRow;

        /**
         * 줄별 좌석 수. 실제 극장은 뒤로 갈수록 넓어지는 부채꼴이라 줄마다 좌석 수가 다르다.
         * {@code [30, 31, 32, ...]} 처럼 앞줄부터 차례로 적는다.
         *
         * <p>이걸 쓰면 rows/seatsPerRow 는 필요없다. 둘 중 하나만 적어야 한다.
         */
        private List<Integer> seatCounts;

        /** 구역 등급. 블록을 나눈 구역에서는 중앙 블록 등급이 된다. */
        private SeatGrade grade;

        /**
         * 무대 기준 위치. 생략하면 CENTER 다 — 무대 정면 한 덩어리인 홀은 아무것도 적을 필요가 없다.
         *
         * <p>여기 적은 순서가 곧 화면 표시 순서다. 전에는 좌석 조회의 {@code ORDER BY section} 에
         * 기대서 구역명 사전순으로 그렸고, 그 탓에 무대에서 먼 구역이 위에 오지 않게 하려고
         * 이름에 접두어 트릭("1층" -> "1층뒤")을 써야 했다. 순서는 이름이 아니라 좌석도가
         * 정하는 게 맞다.
         */
        private SectionPosition position = SectionPosition.CENTER;

        /**
         * 중앙 블록 좌석 수(고정). 지정하면 각 줄이 좌 | 중앙 | 우 세 블록으로 나뉘고
         * 블록 사이에 통로가 생긴다. 지정하지 않으면 통로 없는 한 덩어리다.
         *
         * <p><b>왜 중앙을 고정하는가</b> — 부채꼴에서 통로가 세로로 정렬되려면 중앙 블록
         * 폭이 모든 줄에서 같아야 한다. 줄은 가운데 정렬되므로, 중앙 블록이 고정이면 그
         * 경계가 항상 같은 위치에 오고 좌우 블록이 부채꼴 변화를 흡수한다. 반대로 좌우를
         * 고정하면 줄이 넓어질 때마다 경계가 밀려 통로가 지그재그가 된다.
         * 실제 대형 홀도 중앙 직사각형 + 좌우 쐐기 형태다.
         */
        private Integer centerSeats;

        /** 좌우 블록 등급. centerSeats 를 쓸 때 필수. 중앙과 같은 등급을 줘도 된다(통로만 목적). */
        private SeatGrade sideGrade;

        /** 줄별 좌석 수. seatCounts 가 있으면 그대로, 없으면 rows x seatsPerRow 직사각형. */
        public List<Integer> rowSizes() {
            return hasSeatCounts() ? seatCounts : Collections.nCopies(rows, seatsPerRow);
        }

        public boolean hasSeatCounts() {
            return seatCounts != null && !seatCounts.isEmpty();
        }

        public boolean hasBlocks() {
            return centerSeats != null && centerSeats > 0;
        }
    }
}
