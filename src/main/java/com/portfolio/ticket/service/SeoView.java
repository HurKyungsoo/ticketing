package com.portfolio.ticket.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticket.domain.Performance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공유 카드(Open Graph)와 검색 노출(schema.org)에 쓸 메타 정보.
 *
 * <p>종전에는 메타 태그가 하나도 없어서 카톡·슬랙에 링크를 붙여도 카드가 뜨지 않았고,
 * 검색엔진에는 모든 페이지가 제목만 다른 같은 문서로 보였다. 화면에 이미 있는 값
 * (공연명·포스터·공연장·기간·최저가)을 그대로 쓰므로 새로 구할 데이터는 없다.
 *
 * <p>OG 는 크롤러가 <b>바깥에서</b> 가져가므로 이미지·URL 이 전부 절대주소여야 한다.
 * 서버가 자기 주소를 알아야 하는데 Thymeleaf 3.1 부터는 템플릿에서 {@code #request} 에
 * 접근할 수 없어졌다 — 그래서 컨트롤러가 baseUrl 을 만들어 넘긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoView {

    /** 검색결과에 잘리지 않는 길이. 넘으면 말줄임한다. */
    private static final int MAX_DESCRIPTION = 160;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DOT_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final ObjectMapper objectMapper;

    /**
     * @param jsonLd schema.org 구조화 데이터. 없으면 null 이고 템플릿이 script 를 통째로 건너뛴다.
     */
    public record Meta(String title, String description, String imageUrl, String canonicalUrl, String jsonLd) {}

    /** 홈·목록처럼 특정 공연에 묶이지 않는 화면. 구조화 데이터는 붙이지 않는다. */
    public Meta forSite(String baseUrl, String path, String title, String description) {
        return new Meta(title, description, null, baseUrl + path, null);
    }

    public Meta forPerformance(Performance performance, PerformanceSummaryView summary, String baseUrl) {
        String canonical = baseUrl + "/performances/" + performance.getId();
        String image = imageUrlOf(performance, baseUrl);
        String description = descriptionOf(performance, summary);

        return new Meta(performance.getTitle(), description, image, canonical,
                jsonLdOf(performance, summary, canonical, image, description));
    }

    /**
     * 포스터는 프록시를 거친 주소를 쓴다 — 화면이 그리는 것과 같은 경로라 원본이 핫링크를
     * 막아도 카드 이미지가 깨지지 않는다. 포스터가 없으면 {@code og:image} 를 아예 빼는데,
     * 파비콘 같은 걸 억지로 채우면 공유 카드에 로고만 덩그러니 남아 오히려 안 좋다.
     */
    private String imageUrlOf(Performance performance, String baseUrl) {
        String posterUrl = performance.getPosterUrl();
        if (posterUrl == null || posterUrl.isBlank()) return null;
        return baseUrl + "/img/poster?u=" + URLEncoder.encode(posterUrl, StandardCharsets.UTF_8);
    }

    /**
     * 수집 원본에 줄거리가 없는 공연이 많다(KOPIS {@code sty} 가 비는 경우). 그때 설명을
     * 통째로 비우면 공유 카드가 제목 한 줄만 남으므로, 화면에 이미 보이는 사실
     * (공연장 · 기간 · 최저가)로 문장을 만든다.
     */
    private String descriptionOf(Performance performance, PerformanceSummaryView summary) {
        String raw = performance.getDescription();
        if (raw != null && !raw.isBlank()) {
            return truncate(raw.replaceAll("\\s+", " ").trim());
        }

        StringBuilder sb = new StringBuilder();
        if (performance.getVenue() != null && !performance.getVenue().isBlank()) {
            sb.append(performance.getVenue()).append(" · ");
        }
        sb.append(performance.getStartDate().format(DOT_DATE))
          .append(" – ")
          .append(performance.getEndDate().format(DOT_DATE));
        if (summary.lowestPrice() != null) {
            sb.append(" · ")
              .append(summary.lowestPrice() == 0
                      ? "무료"
                      : String.format("%,d원부터", summary.lowestPrice()));
        }
        return truncate(sb.toString());
    }

    private String truncate(String text) {
        return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION - 1) + "…";
    }

    /**
     * schema.org/Event. 구글이 공연을 이벤트 리치 결과로 인식하는 형식이다.
     *
     * <p>직렬화는 Jackson 에 맡긴다 — 따옴표·역슬래시·제어문자 이스케이프를 직접 짜면
     * 공연명에 따옴표가 든 순간(실데이터에 흔하다) JSON 이 깨진다. 그 위에 {@code <} 를
     * 유니코드로 한 번 더 바꾸는데, 제목에 {@code </script>} 가 들어 있으면 스크립트 블록이
     * 거기서 끝나버려 뒷부분이 그대로 문서에 붙기 때문이다.
     */
    private String jsonLdOf(Performance performance, PerformanceSummaryView summary,
                             String canonical, String image, String description) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("@context", "https://schema.org");
        event.put("@type", "Event");
        event.put("name", performance.getTitle());
        event.put("description", description);
        event.put("url", canonical);
        if (image != null) event.put("image", image);

        // 가장 빠른 회차가 있으면 그 시각을, 없으면 공연 시작일을 쓴다. 회차 쪽이 정확하다
        // (KOPIS dtguidance 에서 온 실제 상연 시각이라 분 단위까지 맞는다).
        event.put("startDate", summary.nextSchedule() != null
                ? summary.nextSchedule().getShowAt().format(ISO)
                : performance.getStartDate().atStartOfDay().format(ISO));
        event.put("endDate", performance.getEndDate().atTime(23, 59, 59).format(ISO));
        event.put("eventStatus", "https://schema.org/EventScheduled");
        event.put("eventAttendanceMode", "https://schema.org/OfflineEventAttendanceMode");

        if (performance.getVenue() != null && !performance.getVenue().isBlank()) {
            Map<String, Object> place = new LinkedHashMap<>();
            place.put("@type", "Place");
            place.put("name", performance.getVenue());
            if (performance.getAddress() != null && !performance.getAddress().isBlank()) {
                Map<String, Object> address = new LinkedHashMap<>();
                address.put("@type", "PostalAddress");
                address.put("streetAddress", performance.getAddress());
                address.put("addressCountry", "KR");
                place.put("address", address);
            }
            event.put("location", place);
        }

        if (summary.lowestPrice() != null) {
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("@type", "Offer");
            offer.put("price", summary.lowestPrice());
            offer.put("priceCurrency", "KRW");
            offer.put("url", canonical);
            // 예매할 수 있는 회차가 하나도 안 남았으면 재고 없음이다. 남은 좌석 수가 아니라
            // "지금 살 수 있는가"를 뜻하므로 회차 유무로 판단한다.
            offer.put("availability", summary.nextSchedule() != null
                    ? "https://schema.org/InStock"
                    : "https://schema.org/SoldOut");
            event.put("offers", offer);
        }

        try {
            return objectMapper.writeValueAsString(event).replace("<", "\\u003C");
        } catch (JsonProcessingException e) {
            // 구조화 데이터가 없다고 페이지가 안 뜰 이유는 없다. 조용히 빼고 로그만 남긴다.
            log.warn("JSON-LD 직렬화 실패. performanceId={}", performance.getId(), e);
            return null;
        }
    }
}
