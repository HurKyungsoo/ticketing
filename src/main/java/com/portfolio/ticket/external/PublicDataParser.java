package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
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

    /** 이름(&amp;) · 10진(&#183;) · 16진(&#xB7;) 세 형태를 한 번에 잡는다. */
    private static final Pattern HTML_ENTITY =
            Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});");

    /** 실데이터에서 실제로 나온 것들 + 흔한 것들. 모르는 이름은 손대지 않고 그대로 둔다. */
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"",
            "apos", "'", "nbsp", " ", "middot", "·");

    /**
     * HTML 엔티티를 실제 문자로 되돌린다.
     *
     * <p>원본이 XML 안에서 한 번 더 인코딩해 보내기 때문에(&amp;amp;lt;) XmlMapper 가 한 겹 벗겨도
     * {@code &lt;} 가 남아, 공연명이 화면에 "연극 &amp;lt;렁스&amp;gt;" 로 그대로 나오고 있었다.
     *
     * <p><b>반드시 한 번만 푼다.</b> 정규식으로 한 번 훑으면서 찾은 자리를 치환하고 그 뒤부터
     * 이어서 스캔하므로, 치환 결과로 새로 생긴 엔티티는 다시 풀리지 않는다({@code &amp;amp;lt;} →
     * {@code &lt;} 에서 멈춘다). {@code replace()} 를 순서대로 여러 번 부르는 방식은 앞의 치환이
     * 만들어낸 문자열을 뒤의 치환이 또 풀어서 원본에 없던 태그가 생길 수 있다.
     *
     * <p>디코딩한 값을 화면에 내보내는 건 안전하다 — 템플릿이 전부 {@code th:text} 라
     * 출력 시점에 다시 이스케이프된다({@code th:utext} 는 이 프로젝트에 한 곳도 없다).
     */
    public String decodeEntities(String raw) {
        if (raw == null || raw.indexOf('&') < 0) return raw;

        Matcher matcher = HTML_ENTITY.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement;
            if (token.charAt(0) == '#') {
                replacement = codePointOf(token, matcher.group());
            } else {
                replacement = NAMED_ENTITIES.getOrDefault(
                        token.toLowerCase(Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 숫자 참조(&#183; / &#xB7;)를 문자로. 범위를 벗어나면 원문 그대로 둔다. */
    private String codePointOf(String token, String original) {
        try {
            boolean hex = token.charAt(1) == 'x' || token.charAt(1) == 'X';
            int codePoint = Integer.parseInt(token.substring(hex ? 2 : 1), hex ? 16 : 10);
            return Character.isValidCodePoint(codePoint)
                    ? new String(Character.toChars(codePoint))
                    : original;
        } catch (RuntimeException e) {
            return original;
        }
    }

    public String text(JsonNode node, String... candidateKeys) {
        return decodeEntities(rawText(node, candidateKeys));
    }

    /**
     * 엔티티를 풀지 않은 원문. 화면에 나가는 값이 아니라 <b>키</b>를 만들 때 쓴다.
     *
     * <p>표준데이터는 원본에 고유키가 없어 공연명+장소+시작일 해시를 external_id 로 쓰는데,
     * 그 해시가 디코딩된 제목으로 바뀌면 이미 적재된 행과 매칭되지 않아 같은 공연이 한 벌 더
     * 쌓인다. 키는 사람이 읽을 값이 아니므로 원문 기준으로 고정해 두는 편이 안전하다.
     */
    public String rawText(JsonNode node, String... candidateKeys) {
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
        // 태그를 먼저 걷어낸 뒤에 엔티티를 푼다. 순서를 바꾸면 &lt;b&gt; 가 진짜 태그로 변한 뒤
        // 태그 제거에 걸려서, 원문에 있던 글자가 통째로 사라진다.
        String raw = rawText(node, candidateKeys);
        if (raw == null) return null;

        String plain = decodeEntities(
                HTML_TAG_PATTERN.matcher(BR_TAG_PATTERN.matcher(raw).replaceAll("\n")).replaceAll(""))
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
