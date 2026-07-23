package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 한눈에보는문화정보 클라이언트.
 * 썸네일 이미지와 장르가 정리돼 있어 목록 UI 품질을 올리는 용도.
 * 객석수는 없으므로 좌석 생성 시 기본값(200석)이 적용된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CulturePerformanceClient {

    private static final String SOURCE_PREFIX = "CIA-";

    private final RestClient publicDataRestClient;
    private final PublicDataProperties properties;
    private final PublicDataParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ExternalPerformance> fetchPage(int pageNo) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getCultureUrl())
                .queryParam("serviceKey", properties.getServiceKey())
                .queryParam("numOfRows", properties.getSyncPageSize())
                .queryParam("pageNo", pageNo)
                .build(true)
                .toUri();

        try {
            String body = publicDataRestClient.get().uri(uri).retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.warn("문화정보 API 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return List.of();
        }
    }

    private List<ExternalPerformance> parse(String body) throws Exception {
        if (body == null || body.isBlank() || body.stripLeading().startsWith("<")) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.path("response").path("body").path("items").path("item");
        if (!items.isArray()) return List.of();

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : items) {
            ExternalPerformance parsed = ExternalPerformance.builder()
                    .externalId(SOURCE_PREFIX + parser.text(item, "localId", "identifier", "id"))
                    .title(parser.text(item, "title", "제목"))
                    .genre(parser.text(item, "subjectCategory", "genre"))
                    .venue(parser.text(item, "eventSite", "spatialCoverage"))
                    .address(parser.text(item, "address"))
                    .startDate(parser.date(item, "eventPeriodStart", "period"))
                    .endDate(parser.date(item, "eventPeriodEnd", "period"))
                    .posterUrl(parser.text(item, "referenceIdentifier", "imageObject", "thumbnail"))
                    .basePrice(parser.price(item, "charge", "price"))
                    .build();

            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return result;
    }
}
