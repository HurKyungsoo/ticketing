package com.portfolio.ticket;

import com.portfolio.ticket.domain.BookingLink;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공연 상세의 예매처 안내(KOPIS 공식 상세 페이지 / 실제 예매처 링크). 이 사이트의
 * 회차·좌석·가격은 SeatGenerator 가 만든 추정치라, KOPIS 에서 수집된 공연만 원본
 * mt20id 로 공식 페이지를, relates 가 있으면 실제 예매처를 안내한다.
 */
class PerformanceKopisLinkTest {

    private Performance performance(SourceType sourceType, String externalId) {
        return Performance.builder()
                .title("테스트 공연")
                .sourceType(sourceType)
                .externalId(externalId)
                .build();
    }

    @Test
    @DisplayName("KOPIS 수집분은 externalId 의 KOPIS- 접두어를 뗀 mt20id 를 돌려준다")
    void kopisSourced() {
        Performance performance = performance(SourceType.KOPIS, "KOPIS-PF288622");

        assertThat(performance.kopisId()).isEqualTo("PF288622");
    }

    @Test
    @DisplayName("표준데이터/문화정보/시드는 KOPIS 대응 레코드가 없으므로 null")
    void nonKopisSourced() {
        assertThat(performance(SourceType.STANDARD, "STD-1234").kopisId()).isNull();
        assertThat(performance(SourceType.CULTURE, "CIA-1234").kopisId()).isNull();
        assertThat(performance(SourceType.SEED, "SEED-1").kopisId()).isNull();
    }

    @Test
    @DisplayName("새로 만든 공연은 bookingLinks 가 빈 목록이다")
    void newPerformanceHasNoBookingLinksByDefault() {
        assertThat(performance(SourceType.KOPIS, "KOPIS-PF1").getBookingLinks()).isEmpty();
    }

    @Test
    @DisplayName("재수집 시 bookingLinks 는 통째로 교체된다 — 지난 회 값이 남지 않는다")
    void updateFromExternalReplacesBookingLinks() {
        Performance performance = performance(SourceType.KOPIS, "KOPIS-PF288622");
        updateWithLinks(performance, List.of(new BookingLink("춘천문화재단", "http://cccf.example/rsv")));

        assertThat(performance.getBookingLinks())
                .extracting(BookingLink::getName, BookingLink::getUrl)
                .containsExactly(org.assertj.core.api.Assertions.tuple("춘천문화재단", "http://cccf.example/rsv"));

        // 다음 수집에서 relates 가 다른 예매처로 바뀌면 지난 값이 남지 않고 새 값으로 완전히 교체돼야 한다.
        updateWithLinks(performance, List.of(new BookingLink("인터파크", "http://interpark.example/rsv")));

        assertThat(performance.getBookingLinks())
                .extracting(BookingLink::getName)
                .containsExactly("인터파크");
    }

    @Test
    @DisplayName("relates 가 없어지면(KOPIS 응답에서 사라짐) bookingLinks 는 빈 목록이 된다")
    void updateFromExternalClearsBookingLinksWhenGone() {
        Performance performance = performance(SourceType.KOPIS, "KOPIS-PF288622");
        updateWithLinks(performance, List.of(new BookingLink("춘천문화재단", "http://cccf.example/rsv")));

        updateWithLinks(performance, List.of());

        assertThat(performance.getBookingLinks()).isEmpty();
    }

    private void updateWithLinks(Performance performance, List<BookingLink> bookingLinks) {
        performance.updateFromExternal(
                "제목", "서양음악(클래식)", "공연장",
                null, null, "주소", null, null,
                LocalDate.now(), LocalDate.now().plusDays(30),
                null, null, null, null, null, null, null,
                SourceType.KOPIS, null, "서울특별시", bookingLinks);
    }
}
