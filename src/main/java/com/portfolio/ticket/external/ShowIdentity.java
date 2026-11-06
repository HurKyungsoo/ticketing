package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SourceType;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 서로 다른 공공데이터 소스에서 온 두 항목이 "같은 공연"인지 판별한다.
 *
 * <p>같은 공연이 KOPIS 와 문화정보에서 따로 들어와 목록에 두 번 떴다 —
 * {@code 다이브 [대학로]}(KOPIS) 와 {@code [대학로] 다이브}(문화정보) 처럼 기관마다
 * 지역 표기 위치가 다르고, 공연장도 {@code 링크더스페이스(LINK THE SPACE)} /
 * {@code 링크더스페이스(LINK THE SPACE) 1관} 처럼 조금씩 다르다. externalId 는 소스마다
 * 체계가 달라 서로 비교할 수 없으니 제목·공연장·기간으로 맞춘다.
 *
 * <p><b>정확도 쪽에 맞췄다.</b> 다른 공연을 하나로 합쳐 버리면 그 공연이 조용히 사라지므로,
 * 세 조건(정규화한 제목이 같고, 공연장이 같은 곳이며, 기간이 겹친다)을 <b>모두</b> 만족할 때만
 * 같은 공연으로 본다. 놓치는 중복이 조금 남더라도 진짜 공연을 지우는 것보다 낫다.
 */
public record ShowIdentity(String titleKey, String venueKey, LocalDate start, LocalDate end) {

    /** [대학로], 【서울】 같은 대괄호 묶음 — 기관마다 앞에 붙이기도 뒤에 붙이기도 한다 */
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*]|【[^】]*】");
    /** 공연장 이름 뒤 영문 병기 (LINK THE SPACE) 같은 괄호 묶음 */
    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)|（[^）]*）");
    /** 공연장 끝의 관 번호 — "1관", "2관" */
    private static final Pattern HALL_NO = Pattern.compile("\\d+관$");
    /** 비교에 의미 없는 문자 — 공백·문장부호·따옴표·꺾쇠 */
    private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·「」『』<>《》〈〉“”‘’…~]+");

    public static ShowIdentity of(String title, String venue, LocalDate start, LocalDate end) {
        return new ShowIdentity(titleKey(title), venueKey(venue), start, end);
    }

    /**
     * 대괄호 묶음의 <b>위치만</b> 무시한다 — 내용은 그대로 비교한다.
     * {@code 다이브 [대학로]} 와 {@code [대학로] 다이브} 는 같지만,
     * {@code 제1회 ST-NOVA [일반 패키지]} 와 {@code 제1회 ST-NOVA [서울연극협회원 패키지]} 는 다른 상품이다.
     * 그래서 "괄호 밖 본문 | 괄호 안 내용(정렬)" 을 키로 쓴다.
     */
    static String titleKey(String title) {
        if (title == null) return "";
        java.util.List<String> tags = new java.util.ArrayList<>();
        java.util.regex.Matcher m = BRACKETS.matcher(title);
        while (m.find()) tags.add(clean(m.group().substring(1, m.group().length() - 1)));
        String body = clean(BRACKETS.matcher(title).replaceAll(" "));
        if (body.isEmpty()) return "";
        java.util.Collections.sort(tags);
        return body + "|" + String.join(",", tags);
    }

    private static String clean(String s) {
        return NOISE.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
    }

    static String venueKey(String venue) {
        if (venue == null) return "";
        String v = BRACKETS.matcher(venue).replaceAll(" ");
        v = clean(PARENS.matcher(v).replaceAll(" "));
        return HALL_NO.matcher(v).replaceAll("");
    }

    public boolean sameShowAs(ShowIdentity o) {
        if (titleKey.isEmpty() || venueKey.isEmpty() || !titleKey.equals(o.titleKey)) return false;
        // 공연장은 한쪽이 다른 쪽을 포함하면 같은 곳으로 본다 — "예술의전당" / "예술의전당콘서트홀"
        boolean sameVenue = venueKey.contains(o.venueKey) || o.venueKey.contains(venueKey);
        boolean overlaps = start != null && end != null && o.start != null && o.end != null
                && !start.isAfter(o.end) && !o.start.isAfter(end);
        return sameVenue && overlaps;
    }

    /**
     * 둘 다 있을 때 남길 쪽. KOPIS 가 회차 시각·객석수·출연진·공식 링크까지 가장 풍부하고,
     * 표준데이터가 그다음, 문화정보는 기간·장소 위주다. 시드(SEED)는 데모용이라 합치지 않는다.
     */
    public static int rank(SourceType source) {
        if (source == null) return 0;
        return switch (source) {
            case KOPIS -> 3;
            case STANDARD -> 2;
            case CULTURE -> 1;
            case SEED -> 0;
        };
    }
}
