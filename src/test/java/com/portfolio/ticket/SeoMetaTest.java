package com.portfolio.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.service.PerformanceSummaryView;
import com.portfolio.ticket.service.SeoView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 카드(OG)와 구조화 데이터.
 *
 * <p>이 값들은 <b>화면에 안 보인다.</b> 틀려도 브라우저에서는 아무 티가 안 나고, 카톡에
 * 링크를 붙여봐야 알게 된다 — 그래서 눈으로 확인하는 걸 믿을 수 없는 종류다.
 * 특히 절대주소와 script 이스케이프는 틀리는 순간 조용히 망가진다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeoMetaTest {

    private static final String BASE = "https://gaekseok.example.com";

    @Autowired SeoView seoView;
    @Autowired ObjectMapper objectMapper;

    private Performance performance(String title, String description, String posterUrl) {
        return Performance.builder()
                .id(42L)
                .externalId("SEO-1")
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("예술의전당 콘서트홀")
                .address("서울특별시 서초구 남부순환로 2406")
                .region("서울특별시")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 30))
                .posterUrl(posterUrl)
                .description(description)
                .totalSeatCount(2400)
                .basePrice(50_000)
                .build();
    }

    /**
     * @param hasUpcoming 아직 안 지난 회차가 있는지. 최저가는 등급 가격이 없으면
     *                    {@code basePrice}(50,000) 로 떨어진다.
     */
    private PerformanceSummaryView summary(Performance performance, boolean hasUpcoming) {
        List<PerformanceSchedule> schedules = hasUpcoming
                ? List.of(PerformanceSchedule.builder()
                    .performance(performance)
                    .showAt(LocalDateTime.of(2026, 8, 10, 19, 30))
                    .totalSeats(100).remainingSeats(50).build())
                : List.of();
        return PerformanceSummaryView.of(performance, schedules, List.of(), LocalDateTime.of(2026, 8, 3, 0, 0));
    }

    @DisplayName("og:image 와 canonical 은 절대주소다")
    @Test
    void urlsAreAbsolute() {
        Performance p = performance("뮤지컬 캣츠", "설명", "https://cdn.example.com/poster.jpg");
        SeoView.Meta meta = seoView.forPerformance(p, summary(p, true), BASE);

        assertThat(meta.canonicalUrl()).isEqualTo(BASE + "/performances/42");
        // 상대경로면 우리 화면에서는 멀쩡한데 공유 카드에서만 이미지가 안 뜬다.
        assertThat(meta.imageUrl()).startsWith(BASE + "/img/poster?u=");
        // 원본 주소는 인코딩돼서 실려야 한다. 날것으로 붙이면 뒤쪽 파라미터가 잘린다.
        assertThat(meta.imageUrl()).contains("https%3A%2F%2Fcdn.example.com%2Fposter.jpg");
    }

    @DisplayName("포스터가 없으면 og:image 를 만들지 않는다")
    @Test
    void omitsImageWhenNoPoster() {
        Performance p = performance("포스터없는공연", "설명", null);
        assertThat(seoView.forPerformance(p, summary(p, true), BASE).imageUrl()).isNull();
    }

    /** 수집 원본에 줄거리가 없는 공연이 많다. 그때 설명이 비면 공유 카드가 제목만 남는다. */
    @DisplayName("줄거리가 없으면 공연장·기간·최저가로 설명을 만든다")
    @Test
    void fallsBackToFactsWhenNoDescription() {
        Performance p = performance("줄거리없는공연", null, null);
        String description = seoView.forPerformance(p, summary(p, true), BASE).description();

        assertThat(description)
                .contains("예술의전당 콘서트홀")
                .contains("2026.08.01")
                .contains("2026.08.30");
    }

    @DisplayName("설명은 160자를 넘지 않는다")
    @Test
    void truncatesLongDescription() {
        Performance p = performance("긴설명", "가".repeat(500), null);
        assertThat(seoView.forPerformance(p, summary(p, true), BASE).description())
                .hasSizeLessThanOrEqualTo(160)
                .endsWith("…");
    }

    /**
     * 이번 작업에서 가장 위험한 지점. 제목에 {@code </script>} 가 들어 있으면 스크립트 블록이
     * 거기서 끝나버려 JSON 뒷부분이 문서에 그대로 붙는다(= 스크립트 주입).
     */
    @DisplayName("제목의 </script> 가 스크립트 블록을 끊지 못한다")
    @Test
    void escapesScriptBreakout() {
        Performance p = performance("나쁜공연</script><script>alert(1)</script>", "설명", null);
        String jsonLd = seoView.forPerformance(p, summary(p, true), BASE).jsonLd();

        assertThat(jsonLd).doesNotContain("</script>");
        assertThat(jsonLd).doesNotContain("<script>");
        assertThat(jsonLd).contains("\\u003C");
    }

    @DisplayName("제목에 따옴표가 있어도 JSON-LD 가 깨지지 않는다")
    @Test
    void handlesQuotesInTitle() throws Exception {
        Performance p = performance("연극 \"햄릿\" 그리고 \\백슬래시", "설명", null);
        String jsonLd = seoView.forPerformance(p, summary(p, true), BASE).jsonLd();

        // 파싱이 되어야 유효한 구조화 데이터다.
        JsonNode node = objectMapper.readTree(jsonLd);
        assertThat(node.get("name").asText()).isEqualTo("연극 \"햄릿\" 그리고 \\백슬래시");
    }

    @DisplayName("JSON-LD 는 schema.org/Event 형식을 갖춘다")
    @Test
    void producesValidEventJsonLd() throws Exception {
        Performance p = performance("뮤지컬 캣츠", "고양이들의 이야기", "https://cdn.example.com/p.jpg");
        String jsonLd = seoView.forPerformance(p, summary(p, true), BASE).jsonLd();

        JsonNode node = objectMapper.readTree(jsonLd);
        assertThat(node.get("@context").asText()).isEqualTo("https://schema.org");
        assertThat(node.get("@type").asText()).isEqualTo("Event");
        assertThat(node.get("name").asText()).isEqualTo("뮤지컬 캣츠");
        assertThat(node.get("url").asText()).isEqualTo(BASE + "/performances/42");
        assertThat(node.get("location").get("name").asText()).isEqualTo("예술의전당 콘서트홀");
        assertThat(node.get("location").get("address").get("streetAddress").asText())
                .isEqualTo("서울특별시 서초구 남부순환로 2406");
        // 가장 빠른 회차가 있으면 그 시각을 쓴다(공연 시작일 00:00 이 아니라).
        assertThat(node.get("startDate").asText()).isEqualTo("2026-08-10T19:30:00");
        assertThat(node.get("offers").get("priceCurrency").asText()).isEqualTo("KRW");
        assertThat(node.get("offers").get("availability").asText())
                .isEqualTo("https://schema.org/InStock");
    }

    @DisplayName("예매할 회차가 없으면 SoldOut 으로 표시된다")
    @Test
    void marksSoldOutWhenNoUpcomingSchedule() throws Exception {
        Performance p = performance("끝난공연", "설명", null);
        String jsonLd = seoView.forPerformance(p, summary(p, false), BASE).jsonLd();

        JsonNode node = objectMapper.readTree(jsonLd);
        assertThat(node.get("offers").get("availability").asText())
                .isEqualTo("https://schema.org/SoldOut");
    }

    @DisplayName("홈·목록 같은 사이트 페이지는 구조화 데이터를 붙이지 않는다")
    @Test
    void siteMetaHasNoJsonLd() {
        SeoView.Meta meta = seoView.forSite(BASE, "/", "객석", "설명");

        assertThat(meta.jsonLd()).isNull();
        assertThat(meta.imageUrl()).isNull();
        assertThat(meta.canonicalUrl()).isEqualTo(BASE + "/");
    }
}
