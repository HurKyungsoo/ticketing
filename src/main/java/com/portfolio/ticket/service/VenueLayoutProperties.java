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

        boolean rectangle = section.getRows() > 0 && section.getSeatsPerRow() > 0;
        if (section.hasSeatCounts() == rectangle) {
            throw new IllegalStateException(
                    "seatCounts 와 rows/seatsPerRow 중 정확히 하나만 적어야 합니다. venue-layouts.yml: " + at);
        }
        if (section.hasSeatCounts() && section.getSeatCounts().stream().anyMatch(n -> n == null || n <= 0)) {
            throw new IllegalStateException("seatCounts 에 0 이하 값이 있습니다. venue-layouts.yml: " + at);
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

        private SeatGrade grade;

        /** 줄별 좌석 수. seatCounts 가 있으면 그대로, 없으면 rows x seatsPerRow 직사각형. */
        public List<Integer> rowSizes() {
            return hasSeatCounts() ? seatCounts : Collections.nCopies(rows, seatsPerRow);
        }

        public boolean hasSeatCounts() {
            return seatCounts != null && !seatCounts.isEmpty();
        }
    }
}
