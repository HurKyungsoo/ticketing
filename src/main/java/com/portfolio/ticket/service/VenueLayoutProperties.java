package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.SeatGrade;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 공연장명 -> 실제 구역 구조(venue-layouts.yml). SeatGenerator 가 참조한다.
 * 일치하는 공연장명이 없으면 기본 좌석 생성 로직으로 대체된다.
 *
 * venueName 을 Map 키로 쓰면 한글 키 바인딩 시 Spring 이 중첩 제네릭 타입을
 * 못 찾는 경우가 있어(ConverterNotFoundException), List + venueName 필드 구조로 우회했다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "venue")
public class VenueLayoutProperties {

    private List<VenueLayout> layouts = new ArrayList<>();

    public List<SectionLayout> findSections(String venueName) {
        if (venueName == null) return null;
        String trimmed = venueName.trim();
        return layouts.stream()
                .filter(v -> trimmed.equals(v.getVenueName() == null ? null : v.getVenueName().trim()))
                .map(VenueLayout::getSections)
                .findFirst()
                .orElse(null);
    }

    @Getter
    @Setter
    public static class VenueLayout {
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
