package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.portfolio.ticket.domain.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 한눈에보는문화정보 클라이언트 (기간별 조회, /period2).
 * 썸네일 이미지와 장르가 정리돼 있어 목록 UI 품질을 올리는 용도.
 * 객석수는 없으므로 좌석 생성 시 기본값(200석)이 적용된다.
 *
 * 주의: 이 API 는 정상 응답도 XML 만 내려준다. JSON 이 아니라고 해서
 *       오류 응답이 아니다 (표준데이터 API 와 다른 점).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CulturePerformanceClient {

    private static final String SOURCE_PREFIX = "CIA-";
    private static final DateTimeFormatter DATE_PARAM_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient publicDataRestClient;
    private final PublicDataProperties properties;
    private final PublicDataParser parser;
    private final XmlMapper xmlMapper = new XmlMapper();

    public List<ExternalPerformance> fetchPage(int pageNo) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusYears(1);

        URI uri = UriComponentsBuilder.fromUriString(properties.getCultureUrl())
                .queryParam("serviceKey", properties.getServiceKey())
                .queryParam("from", from.format(DATE_PARAM_FORMAT))
                .queryParam("to", to.format(DATE_PARAM_FORMAT))
                // 페이징 파라미터명이 KOPIS(cpage/rows)와 다르다. 이 API 는 PageNo/numOfrows 만 인식하고
                // 모르는 이름은 조용히 무시한 채 1페이지 10건을 돌려주므로, 틀리면 같은 10건을 계속 받게 된다.
                .queryParam("PageNo", pageNo)
                .queryParam("numOfrows", properties.getSyncPageSize())
                .build(true)
                .toUri();

        try {
            byte[] body = publicDataRestClient.get().uri(uri).retrieve().body(byte[].class);
            return parse(body);
        } catch (Exception e) {
            log.warn("문화정보 API 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return List.of();
        }
    }

    private List<ExternalPerformance> parse(byte[] body) throws Exception {
        if (body == null || body.length == 0) return List.of();

        JsonNode root = xmlMapper.readTree(body);

        JsonNode header = root.path("header");
        String resultCode = header.path("resultCode").asText(null);
        if (resultCode != null && !resultCode.equals("00")) {
            log.warn("문화정보 API 서비스 오류 응답. resultCode={}, resultMsg={}",
                    resultCode, header.path("resultMsg").asText());
            return List.of();
        }

        JsonNode items = root.path("body").path("items").path("item");
        if (items.isMissingNode()) {
            log.debug("문화정보 응답에 item 노드 없음");
            return List.of();
        }
        // item 이 1건이면 XmlMapper 가 배열이 아니라 단일 노드로 파싱한다.
        List<JsonNode> itemNodes = items.isArray() ? toList(items) : List.of(items);

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            ExternalPerformance parsed = ExternalPerformance.builder()
                    .externalId(SOURCE_PREFIX + parser.text(item, "seq"))
                    .sourceType(SourceType.CULTURE)
                    .title(parser.text(item, "title"))
                    .genre(parser.text(item, "realmName"))
                    .venue(parser.text(item, "place"))
                    .address(joinNonBlank(parser.text(item, "area"), parser.text(item, "sigungu")))
                    .region(parser.text(item, "area"))
                    .latitude(parser.decimal(item, "gpsY"))
                    .longitude(parser.decimal(item, "gpsX"))
                    .startDate(parser.date(item, "startDate"))
                    .endDate(parser.date(item, "endDate"))
                    .posterUrl(parser.text(item, "thumbnail"))
                    .build();

            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return result;
    }

    private List<JsonNode> toList(JsonNode arrayNode) {
        List<JsonNode> list = new ArrayList<>();
        arrayNode.forEach(list::add);
        return list;
    }

    /** period2 에는 상세 주소가 없고 area(지역)/sigungu(시군구)만 내려온다. */
    private String joinNonBlank(String area, String sigungu) {
        if (area == null || area.isBlank()) return sigungu;
        if (sigungu == null || sigungu.isBlank()) return area;
        return area + " " + sigungu;
    }
}
