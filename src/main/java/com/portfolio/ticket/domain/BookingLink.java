package com.portfolio.ticket.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * KOPIS 상세(relates)가 알려주는 실제 예매처. 공연 하나에 여러 곳(공연장 자체 예매처,
 * 인터파크 등)이 붙을 수 있어 {@link Performance#getBookingLinks()} 로 목록을 갖는다.
 *
 * <p>이 사이트 자체는 예매처가 아니라서(회차·좌석·가격이 추정치) 상세 화면은 이 목록을
 * "진짜 예매 가능한 곳"으로 안내한다 — KOPIS 자체 상세 페이지 링크보다 한 단계 더 정확하다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class BookingLink {

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "url", length = 500, nullable = false)
    private String url;
}
