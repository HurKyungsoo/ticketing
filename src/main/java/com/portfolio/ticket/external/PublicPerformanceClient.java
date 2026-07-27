package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
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
    private final PublicDataErrorCode errorCode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FetchResult fetchPage(int pageNo) {
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
        } catch (HttpStatusCodeException e) {
            log.warn("공연 표준데이터 호출 실패. pageNo={}, status={}, body(500자)={}",
                    pageNo, e.getStatusCode(), preview(e.getResponseBodyAsString()));
            return FetchResult.empty();
        } catch (Exception e) {
            log.warn("공연 표준데이터 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return FetchResult.empty();
        }
    }

    private FetchResult parse(String body) throws Exception {
        if (body == null || body.isBlank()) return FetchResult.empty();

        // 인증키 오류 등은 JSON 이 아니라 XML(구형 게이트웨이)로 떨어지기도 한다.
        if (body.stripLeading().startsWith("<")) {
            String readable = errorCode.describeXml(body);
            log.warn("JSON 이 아닌 응답 수신{}. body(500자)={}",
                    readable != null ? " - " + readable : " (인증키/트래픽 초과 가능성)",
                    preview(body));
            return FetchResult.empty();
        }

        JsonNode root = objectMapper.readTree(body);

        // 신형 게이트웨이는 HTTP 200 을 유지한 채 response.header.resultCode 로 에러를 알린다.
        String readable = errorCode.describeJson(root);
        if (readable != null) {
            log.warn("공공데이터포털 에러 응답 수신 - {}. body(500자)={}", readable, preview(body));
            return FetchResult.empty();
        }

        JsonNode items = root.path("response").path("body").path("items");
        if (items.isMissingNode() || !items.isArray()) {
            log.debug("items 노드 없음");
            return FetchResult.empty();
        }

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : items) {
            ExternalPerformance parsed = toExternal(item);
            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return new FetchResult(items.size(), result);
    }

    private ExternalPerformance toExternal(JsonNode item) {
        String title = parser.text(item, "eventNm", "공연행사명", "title");

        return ExternalPerformance.builder()
                .externalId(SOURCE_PREFIX + buildKey(item, title))
                .title(title)
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

    /** 원본에 고유키가 없는 경우가 많아 공연명+장소+시작일로 대체키를 만든다. */
    private String buildKey(JsonNode item, String title) {
        String venue = parser.text(item, "opar", "장소");
        String start = parser.text(item, "eventStartDate", "행사시작일자");
        return Integer.toHexString((title + "|" + venue + "|" + start).hashCode());
    }

    private String preview(String body) {
        if (body == null) return "";
        return body.substring(0, Math.min(500, body.length()));
    }
}
