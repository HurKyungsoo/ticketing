package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 서울 열린데이터광장 "문화행사 정보"(culturalEventInfo) 클라이언트.
 * 공공데이터포털 두 소스와 다른 기관이라 인증키 승인 문제가 따로 나도
 * 이 소스는 영향을 받지 않는 이중화 목적의 세 번째 소스.
 *
 * 이 API 는 serviceKey 를 쿼리파라미터가 아니라 URL 경로에 넣는다:
 *   {baseUrl}/{serviceKey}/json/culturalEventInfo/{시작인덱스}/{종료인덱스}/
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulCultureClient {

    private static final String SOURCE_PREFIX = "SEOUL-";

    private final RestClient publicDataRestClient;
    private final SeoulOpenApiProperties properties;
    private final PublicDataParser parser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FetchResult fetchPage(int pageNo) {
        int start = (pageNo - 1) * properties.getSyncPageSize() + 1;
        int end = start + properties.getSyncPageSize() - 1;

        URI uri = URI.create(String.format("%s/%s/json/culturalEventInfo/%d/%d/",
                properties.getBaseUrl(), properties.getServiceKey(), start, end));

        try {
            String body = publicDataRestClient.get().uri(uri).retrieve().body(String.class);
            return parse(body);
        } catch (Exception e) {
            log.warn("서울 열린데이터광장 문화행사 API 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return FetchResult.empty();
        }
    }

    private FetchResult parse(String body) throws Exception {
        if (body == null || body.isBlank()) return FetchResult.empty();

        // 인증키 오류/트래픽 초과는 이 API 도 JSON 이 아닌 응답으로 떨어질 수 있다.
        if (body.stripLeading().startsWith("<")) {
            log.warn("JSON 이 아닌 응답 수신 (인증키/트래픽 초과 가능성): {}",
                    body.substring(0, Math.min(200, body.length())));
            return FetchResult.empty();
        }

        JsonNode root = objectMapper.readTree(body);
        JsonNode rows = root.path("culturalEventInfo").path("row");
        if (!rows.isArray()) {
            log.debug("row 노드 없음. body={}", body.substring(0, Math.min(200, body.length())));
            return FetchResult.empty();
        }

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode row : rows) {
            ExternalPerformance parsed = toExternal(row);
            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return new FetchResult(rows.size(), result);
    }

    private ExternalPerformance toExternal(JsonNode row) {
        String title = parser.text(row, "TITLE", "제목");

        return ExternalPerformance.builder()
                .externalId(SOURCE_PREFIX + buildKey(row, title))
                .title(title)
                .genre(parser.text(row, "CODENAME", "THEMECODE", "분류"))
                .venue(parser.text(row, "PLACE", "장소"))
                .address(parser.text(row, "GUNAME", "자치구"))
                .latitude(parser.decimal(row, "LAT", "위도"))
                .longitude(parser.decimal(row, "LOT", "경도"))
                .startDate(seoulDate(row, "STRTDATE", "시작일"))
                .endDate(seoulDate(row, "END_DATE", "종료일"))
                .posterUrl(parser.text(row, "MAIN_IMG", "이미지"))
                .basePrice(parser.price(row, "USE_FEE", "IS_FREE", "이용요금"))
                .build();
    }

    /**
     * STRTDATE/END_DATE 는 "2024-01-01 00:00:00.0" 처럼 시각까지 붙어 있어
     * PublicDataParser.date() 의 4개 포맷 어디에도 안 맞는다. 날짜 부분만 잘라서 쓴다.
     */
    private LocalDate seoulDate(JsonNode row, String... candidateKeys) {
        String raw = parser.text(row, candidateKeys);
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw.split(" ")[0]);
        } catch (Exception e) {
            log.debug("날짜 파싱 실패: {}", raw);
            return null;
        }
    }

    /** 원본에 고유키가 없어 공연명+장소+시작일로 대체키를 만든다. */
    private String buildKey(JsonNode row, String title) {
        String venue = parser.text(row, "PLACE", "장소");
        String start = parser.text(row, "STRTDATE", "시작일");
        return Integer.toHexString((title + "|" + venue + "|" + start).hashCode());
    }
}
