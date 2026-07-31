package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticket.domain.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 전국공연행사정보표준데이터 클라이언트.
 * 객석수 / 관람요금 / 위경도를 주기 때문에 좌석 생성의 기준 데이터가 된다.
 *
 * 주의: serviceKey 는 이미 URL 인코딩된 문자열이므로 재인코딩하면 안 된다.
 *       (UriComponentsBuilder.build(true) 사용)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublicPerformanceClient {

    private static final String SOURCE_PREFIX = "STD-";

    private final RestClient publicDataRestClient;
    private final PublicDataProperties properties;
    private final PublicDataParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ExternalPerformance> fetchPage(int pageNo) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getPerformanceUrl())
                .queryParam("serviceKey", properties.getServiceKey())
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", properties.getSyncPageSize())
                .queryParam("type", "json")
                .build(true)
                .toUri();

        try {
            String body = publicDataRestClient.get().uri(uri).retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.warn("공연 표준데이터 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return List.of();
        }
    }

    private List<ExternalPerformance> parse(String body) throws Exception {
        if (body == null || body.isBlank()) return List.of();

        // 인증키 오류 등은 JSON 이 아니라 XML 로 떨어진다.
        if (body.stripLeading().startsWith("<")) {
            log.warn("JSON 이 아닌 응답 수신 (인증키/트래픽 초과 가능성): {}",
                    body.substring(0, Math.min(200, body.length())));
            return List.of();
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode header = root.path("response").path("header");
        String resultCode = header.path("resultCode").asText(null);
        if (resultCode != null && !resultCode.equals("00")) {
            log.warn("공공데이터 서비스 오류 응답. resultCode={}, resultMsg={}",
                    resultCode, header.path("resultMsg").asText());
            return List.of();
        }

        JsonNode items = root.path("response").path("body").path("items");
        if (items.isMissingNode() || !items.isArray()) {
            log.debug("items 노드 없음");
            return List.of();
        }

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : items) {
            ExternalPerformance parsed = toExternal(item);
            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return result;
    }

    private ExternalPerformance toExternal(JsonNode item) {
        return ExternalPerformance.builder()
                .externalId(SOURCE_PREFIX + buildKey(item))
                .sourceType(SourceType.STANDARD)
                .title(parser.text(item, "eventNm", "공연행사명", "title"))
                .genre(parser.text(item, "eventCo", "장르"))
                .venue(parser.text(item, "opar", "장소", "eventPlace"))
                .address(parser.text(item, "rdnmadr", "lnmadr", "소재지도로명주소"))
                .latitude(parser.decimal(item, "latitude", "위도"))
                .longitude(parser.decimal(item, "longitude", "경도"))
                .startDate(parser.date(item, "eventStartDate", "행사시작일자"))
                .endDate(parser.date(item, "eventEndDate", "행사종료일자"))
                .posterUrl(parser.text(item, "imgUrl", "포스터"))
                .totalSeatCount(parser.number(item, "seatCo", "객석수"))
                .basePrice(parser.price(item, "admfee", "관람요금", "이용요금"))
                .build();
    }

    /**
     * 원본에 고유키가 없는 경우가 많아 공연명+장소+시작일로 대체키를 만든다.
     *
     * <p>여기서만 {@code rawText} 를 쓴다 — 엔티티를 푼 제목으로 해시하면 이미 적재된 행의
     * external_id 와 달라져서, 같은 공연이 갱신되지 않고 한 벌 더 쌓인다. 키는 사람이 읽을
     * 값이 아니므로 원문 기준으로 고정해 둔다.
     */
    private String buildKey(JsonNode item) {
        String title = parser.rawText(item, "eventNm", "공연행사명", "title");
        String venue = parser.rawText(item, "opar", "장소");
        String start = parser.rawText(item, "eventStartDate", "행사시작일자");
        return Integer.toHexString((title + "|" + venue + "|" + start).hashCode());
    }
}
