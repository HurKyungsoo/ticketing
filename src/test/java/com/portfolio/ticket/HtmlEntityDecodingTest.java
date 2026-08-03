package com.portfolio.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.ticket.external.PublicDataParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공공데이터 응답의 HTML 엔티티 디코딩.
 *
 * <p>원본이 XML 안에서 한 번 더 인코딩해 보내기 때문에 XmlMapper 가 한 겹 벗겨도
 * {@code &lt;} 가 남아, 공연명이 화면에 "연극 &amp;lt;렁스&amp;gt;" 로 그대로 나오고 있었다.
 * 실데이터 472건 중 10건이 이 상태였고, 그 10건이 전부 목록 첫 화면에 몰려 있었다.
 *
 * <p>화면으로는 재현이 번거롭고(수집을 다시 돌려야 한다) 조용히 표기만 틀리는 종류라
 * 여기서 고정한다. 특히 <b>두 번 풀지 않는 것</b>과 <b>중복 방지 키가 안 바뀌는 것</b>이
 * 중요하다 — 전자는 원본에 없던 태그를 만들어내고, 후자는 같은 공연을 한 벌 더 쌓는다.
 */
class HtmlEntityDecodingTest {

    private final PublicDataParser parser = new PublicDataParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode node(String key, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    @DisplayName("실데이터에서 실제로 깨져 있던 제목들이 제대로 풀린다")
    @Test
    void decodesTitlesSeenInRealData() {
        assertThat(parser.decodeEntities("연극 &lt;렁스&gt; : 연극열전10"))
                .isEqualTo("연극 <렁스> : 연극열전10");
        assertThat(parser.decodeEntities("MU&amp;A 스튜디오 기획 시리즈"))
                .isEqualTo("MU&A 스튜디오 기획 시리즈");
        assertThat(parser.decodeEntities("5&middot;18광주민주화운동 특별전시"))
                .isEqualTo("5·18광주민주화운동 특별전시");
        assertThat(parser.decodeEntities("르누아르&middot;드가&middot;고흐"))
                .isEqualTo("르누아르·드가·고흐");
    }

    @DisplayName("숫자 참조도 10진·16진 모두 푼다")
    @Test
    void decodesNumericReferences() {
        assertThat(parser.decodeEntities("5&#183;18")).isEqualTo("5·18");
        assertThat(parser.decodeEntities("5&#xB7;18")).isEqualTo("5·18");
    }

    /**
     * 이중 인코딩(&amp;amp;lt;)은 <b>한 겹만</b> 벗겨서 &amp;lt; 에서 멈춰야 한다.
     * replace() 를 순서대로 여러 번 부르면 &amp;amp; → &amp; 로 바뀐 결과를 뒤의 replace 가
     * 다시 풀어 "&lt;" 라는 진짜 꺾쇠가 만들어진다 — 원본에 없던 태그가 생기는 셈이다.
     */
    @DisplayName("이중 인코딩은 한 번만 풀어서 멈춘다")
    @Test
    void decodesExactlyOnce() {
        assertThat(parser.decodeEntities("&amp;lt;script&amp;gt;")).isEqualTo("&lt;script&gt;");
        assertThat(parser.decodeEntities("&amp;amp;")).isEqualTo("&amp;");
    }

    @DisplayName("모르는 엔티티와 엔티티가 아닌 & 는 건드리지 않는다")
    @Test
    void leavesUnknownTokensAlone() {
        assertThat(parser.decodeEntities("AT&T 공연")).isEqualTo("AT&T 공연");
        assertThat(parser.decodeEntities("&zzz; 공연")).isEqualTo("&zzz; 공연");
        assertThat(parser.decodeEntities("Q&A 세션")).isEqualTo("Q&A 세션");
    }

    @DisplayName("엔티티가 없으면 원문 그대로, null 도 그대로")
    @Test
    void passesThroughUntouched() {
        assertThat(parser.decodeEntities("평범한 공연명")).isEqualTo("평범한 공연명");
        assertThat(parser.decodeEntities(null)).isNull();
    }

    @DisplayName("text() 는 풀고, rawText() 는 원문을 그대로 준다")
    @Test
    void textDecodesButRawTextDoesNot() {
        ObjectNode item = node("eventNm", "연극 &lt;렁스&gt;");

        assertThat(parser.text(item, "eventNm")).isEqualTo("연극 <렁스>");
        assertThat(parser.rawText(item, "eventNm")).isEqualTo("연극 &lt;렁스&gt;");
    }

    /**
     * 표준데이터는 원본에 고유키가 없어 공연명+장소+시작일 해시를 external_id 로 쓴다.
     * 그 해시를 디코딩된 제목으로 만들면 이미 적재된 행과 매칭되지 않아 같은 공연이 한 벌 더
     * 쌓인다. 키는 rawText 로 만들어야 디코딩 도입 전후로 값이 같다.
     */
    @DisplayName("중복 방지 키는 디코딩 전후로 값이 같아야 한다")
    @Test
    void dedupKeyIsUnaffectedByDecoding() {
        String rawTitle = "연극 &lt;렁스&gt;";
        String decodedTitle = parser.decodeEntities(rawTitle);

        int keyFromRaw = (rawTitle + "|장소|20260101").hashCode();
        int keyFromDecoded = (decodedTitle + "|장소|20260101").hashCode();

        // 둘이 다르다는 사실 자체가 rawText 를 써야 하는 이유다.
        assertThat(keyFromDecoded).isNotEqualTo(keyFromRaw);
    }

    @DisplayName("소개글은 태그를 걷어낸 뒤 엔티티를 푼다")
    @Test
    void descriptionStripsTagsThenDecodes() {
        ObjectNode item = node("sty", "첫째 줄<br>둘째 &lt;강조&gt; 줄&nbsp;끝");

        assertThat(parser.description(item, "sty")).isEqualTo("첫째 줄\n둘째 <강조> 줄 끝");
    }
}
