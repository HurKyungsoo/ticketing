package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.SeatGrade;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        private int rows;
        private int seatsPerRow;
        private SeatGrade grade;
    }
}
