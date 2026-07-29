package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공데이터 응답 정제기.
 *
 * 공공데이터 API 는 기관마다 필드명/날짜포맷/인코딩이 제각각이라
 * 파싱 실패가 곧 배치 실패로 이어진다. 후보 키를 순서대로 탐색하고
 * 실패 시 null 을 반환해 한 건 때문에 배치 전체가 죽지 않게 한다.
 */
@Slf4j
@Component
public class PublicDataParser {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    /** "전석 30,000원" / "R석 50000원, S석 30000원" 에서 첫 숫자를 뽑는다. */
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{4,})");

    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    /** Performance.description 컬럼 길이(1000)에 맞춘 상한. DB 저장 전에 여기서 자른다. */
    private static final int DESCRIPTION_MAX_LENGTH = 1000;

    public String text(JsonNode node, String... candidateKeys) {
        for (String key : candidateKeys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String s = value.asText().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    public LocalDate date(JsonNode node, String... candidateKeys) {
        String raw = text(node, candidateKeys);
        if (raw == null) return null;

        String cleaned = raw.replaceAll("[^0-9\\-./]", "");
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format);
            } catch (Exception ignored) {
                // 다음 포맷 시도
            }
        }
        log.debug("날짜 파싱 실패: {}", raw);
        return null;
    }

    public Integer number(JsonNode node, String... candidateKeys) {
        String raw = text(node, candidateKeys);
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    public Double decimal(JsonNode node, String... candidateKeys) {
        String raw = text(node, candidateKeys);
        if (raw == null) return null;
        try {
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 자유 텍스트 설명(KOPIS sty 등 <br> 태그·HTML 엔티티가 섞인 원본)을 평문으로 정제한다.
     * 컬럼 길이를 넘으면 잘라서 반환 — 여기서 안 자르면 저장 시 배치 전체가 죽을 수 있다.
     */
    public String description(JsonNode node, String... candidateKeys) {
        String raw = text(node, candidateKeys);
        if (raw == null) return null;

        String plain = HTML_TAG_PATTERN.matcher(BR_TAG_PATTERN.matcher(raw).replaceAll("\n")).replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (plain.isEmpty()) return null;
        return plain.length() > DESCRIPTION_MAX_LENGTH ? plain.substring(0, DESCRIPTION_MAX_LENGTH) : plain;
    }

    /** 관람요금 문자열에서 기준가를 추출. 무료 공연은 0. */
    public Integer price(JsonNode node, String... candidateKeys) {
        String raw = text(node, candidateKeys);
        if (raw == null) return null;
        if (raw.contains("무료")) return 0;

        Matcher matcher = PRICE_PATTERN.matcher(raw);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1).replace(",", ""));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
