package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 공공데이터포털(data.go.kr 계열) 표준 에러 응답을 사람이 읽을 수 있는 메시지로 바꾼다.
 *
 * 게이트웨이 세대에 따라 응답 형식이 둘로 갈린다.
 *   - 구형: XML {@code <OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>}
 *   - 신형: JSON {@code {"response":{"header":{"resultCode":...}}}} (HTTP 200 인 채로 옴)
 * 인증키/트래픽 문제는 둘 다 HTTP 200 으로 내려오는 경우가 많아서 상태코드만
 * 봐서는 알 수 없고, 응답 바디 안의 코드를 직접 봐야 원인을 알 수 있다.
 */
@Slf4j
@Component
public class PublicDataErrorCode {

    /** 두 형식 모두 같은 공공데이터포털 공통 에러코드 표를 쓴다. */
    private static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("01", "어플리케이션 에러"),
            Map.entry("02", "DB 에러"),
            Map.entry("03", "데이터 없음"),
            Map.entry("04", "HTTP 에러"),
            Map.entry("05", "서비스 타임아웃"),
            Map.entry("10", "잘못된 요청 파라미터"),
            Map.entry("11", "필수 요청 파라미터 누락"),
            Map.entry("12", "해당 오픈API 서비스 없음"),
            Map.entry("20", "서비스 접근 거부"),
            Map.entry("21", "일시적으로 사용 중지된 서비스키"),
            Map.entry("22", "일일 트래픽 초과"),
            Map.entry("30", "등록되지 않은 서비스키 (포털에서 승인됐어도 백엔드 반영 전이면 이 에러가 난다)"),
            Map.entry("31", "기한이 만료된 서비스키"),
            Map.entry("32", "등록되지 않은 IP"),
            Map.entry("33", "서명되지 않은 호출"),
            Map.entry("99", "기타 에러")
    );

    private static final Map<String, String> SUCCESS_CODES = Map.of("00", "", "0000", "");

    private final XmlMapper xmlMapper = new XmlMapper();

    /** 구형 XML 응답이면 사람이 읽을 수 있는 메시지로, 아니면 null. */
    public String describeXml(String body) {
        if (body == null || !body.stripLeading().startsWith("<")) {
            return null;
        }
        try {
            JsonNode header = xmlMapper.readTree(body).path("cmmMsgHeader");
            String code = header.path("returnReasonCode").asText(null);
            if (code == null) return null;

            String authMsg = header.path("returnAuthMsg").asText(null);
            return format(code, authMsg);
        } catch (Exception e) {
            log.debug("표준 에러 응답(XML) 파싱 실패, 형식이 다른 XML 로 보임. msg={}", e.getMessage());
            return null;
        }
    }

    /** 신형 JSON 응답의 response.header.resultCode 가 에러면 사람이 읽을 수 있는 메시지로, 정상/형식 다름이면 null. */
    public String describeJson(JsonNode root) {
        JsonNode header = root.path("response").path("header");
        String code = header.path("resultCode").asText(null);
        if (code == null || SUCCESS_CODES.containsKey(code)) {
            return null;
        }
        String resultMsg = header.path("resultMsg").asText(null);
        return format(code, resultMsg);
    }

    private String format(String code, String rawMsg) {
        String readable = MESSAGES.getOrDefault(code, "알 수 없는 에러 코드");
        return "공공데이터포털 에러 " + code + ": " + readable
                + (rawMsg != null ? " (원본 메시지=" + rawMsg + ")" : "");
    }
}
