package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공연 상세의 "KOPIS 공식 상세 페이지" 링크. 이 사이트의 회차·좌석·가격은 SeatGenerator 가
 * 만든 추정치라, KOPIS 에서 수집된 공연만 원본 mt20id 로 공식 페이지를 안내한다.
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
}
