package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SourceType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
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
 *
 * <p>그 규칙으로 못 잡는 중복이 실데이터(2026-11-13, 상영 중 문화정보 237건)에 30건 남짓 있었다.
 * 문화정보는 제목 앞뒤에 장르·연도를 붙이고({@code 연극 <나와 할아버지>}, {@code 2026 집에 사는 몬스터}),
 * 지역 태그를 KOPIS 와 다르게 달거나 안 달며, 공연장 이름도 개명 전 이름이나 상위 시설 이름을 쓴다
 * ({@code 대학로 드림아트센터} / {@code 링크아트센터드림}). 이런 쌍은 <b>시작일과 종료일이 정확히 같다</b>는
 * 공통점이 있었다 — 같은 제목의 투어 공연({@code 호두까기 인형})은 도시마다 기간이 다르다. 그래서
 * 기간이 정확히 같을 때만 제목·태그·공연장을 느슨하게 맞추는 두 번째 규칙을 둔다({@link #looselySame}).
 */
public record ShowIdentity(String titleKey, String body, List<String> tags, String venueKey, String venueCore,
                           LocalDate start, LocalDate end) {

    /** [대학로], 【서울】 같은 대괄호 묶음 — 기관마다 앞에 붙이기도 뒤에 붙이기도 한다 */
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*]|【[^】]*】");
    /** 공연장 이름 뒤 영문 병기 (LINK THE SPACE) 같은 괄호 묶음 */
    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)|（[^）]*）");
    /** 공연장 끝의 관 번호 — "1관", "2관" */
    private static final Pattern HALL_NO = Pattern.compile("\\d+관$");
    /** 비교에 의미 없는 문자 — 공백·문장부호·따옴표·꺾쇠 */
    private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·「」『』<>《》〈〉“”‘’…~]+");

    /**
     * 공연장 이름에서 어느 곳에나 붙는 말. 이걸 빼고 남은 핵심 이름(드림, 서경, 세종, 디큐브)이 겹치는지 본다.
     * 긴 것부터 지워야 "문화예술회관" 이 "예술" 만 빠지고 남지 않는다.
     */
    private static final List<String> GENERIC_VENUE_WORDS = List.of(
            "문화예술회관", "문화예술센터", "예술회관", "문예회관", "문화회관", "문화센터", "문화재단", "아트센터",
            "소극장", "대극장", "중극장", "씨어터", "시어터", "아트홀", "공연장", "스테이지", "극장", "센터", "회관",
            "공연", "예술", "문화", "대학로", "서울", "국립", "시립", "구립", "도립", "구민", "시민", "어린이", "홀", "관", "층");
    /** 느슨한 제목 비교에서 한쪽이 다른 쪽을 포함한다고 볼 최소 길이. 두 글자 제목은 우연히 겹치기 쉽다. */
    private static final int MIN_CONTAINED_TITLE = 3;

    public static ShowIdentity of(String title, String venue, LocalDate start, LocalDate end) {
        return new ShowIdentity(titleKey(title), body(title), tags(title), venueKey(venue), venueCore(venue), start, end);
    }

    /**
     * 대괄호 묶음의 <b>위치만</b> 무시한다 — 내용은 그대로 비교한다.
     * {@code 다이브 [대학로]} 와 {@code [대학로] 다이브} 는 같지만,
     * {@code 제1회 ST-NOVA [일반 패키지]} 와 {@code 제1회 ST-NOVA [서울연극협회원 패키지]} 는 다른 상품이다.
     * 그래서 "괄호 밖 본문 | 괄호 안 내용(정렬)" 을 키로 쓴다.
     */
    static String titleKey(String title) {
        String body = body(title);
        if (body.isEmpty()) return "";
        return body + "|" + String.join(",", tags(title));
    }

    /** 대괄호 묶음을 뺀 제목 본문(정규화). */
    static String body(String title) {
        if (title == null) return "";
        return clean(BRACKETS.matcher(title).replaceAll(" "));
    }

    /** 대괄호 묶음 안의 내용들(정규화, 정렬). */
    static List<String> tags(String title) {
        if (title == null) return List.of();
        List<String> tags = new ArrayList<>();
        Matcher m = BRACKETS.matcher(title);
        while (m.find()) tags.add(clean(m.group().substring(1, m.group().length() - 1)));
        Collections.sort(tags);
        return List.copyOf(tags);
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

    /**
     * 일반어를 뺀 공연장 핵심 이름. 괄호 안(개명 전 이름 "구. 서경대학교 공연예술센터")도 남긴다 —
     * 옛 이름이 다른 소스의 표기와 맞을 수 있다.
     */
    static String venueCore(String venue) {
        if (venue == null) return "";
        String v = clean(venue);
        for (String word : GENERIC_VENUE_WORDS) v = v.replace(word, "");
        return v;
    }

    public boolean sameShowAs(ShowIdentity o) {
        return strictlySame(o) || looselySame(o);
    }

    private boolean strictlySame(ShowIdentity o) {
        if (titleKey.isEmpty() || venueKey.isEmpty() || !titleKey.equals(o.titleKey)) return false;
        // 공연장은 한쪽이 다른 쪽을 포함하면 같은 곳으로 본다 — "예술의전당" / "예술의전당콘서트홀"
        boolean sameVenue = venueKey.contains(o.venueKey) || o.venueKey.contains(venueKey);
        boolean overlaps = start != null && end != null && o.start != null && o.end != null
                && !start.isAfter(o.end) && !o.start.isAfter(end);
        return sameVenue && overlaps;
    }

    /**
     * 시작일·종료일이 정확히 같을 때만 쓰는 두 번째 규칙. 세 가지를 모두 만족해야 한다.
     * <ul>
     *   <li>제목 본문이 같거나 한쪽이 다른 쪽을 포함한다 — 앞뒤에 붙은 "연극", "2026", "10주년 기념 공연"</li>
     *   <li>대괄호 태그가 어긋나지 않는다 — 한쪽에만 있거나 서로 포함("대구 동성로"/"대구")하면 괜찮고,
     *       둘 다 있는데 전혀 다르면({@code [일반 패키지]}/{@code [회원 패키지]}) 다른 상품이다</li>
     *   <li>공연장 핵심 이름이 두 글자 이상 겹친다 — "드림아트센터"/"링크아트센터드림"</li>
     * </ul>
     */
    private boolean looselySame(ShowIdentity o) {
        if (start == null || end == null || !start.equals(o.start) || !end.equals(o.end)) return false;
        return titlesMatch(body, o.body) && tagsCompatible(tags, o.tags) && venuesOverlap(venueCore, o.venueCore);
    }

    private static boolean titlesMatch(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.length() >= MIN_CONTAINED_TITLE && longer.contains(shorter);
    }

    private static boolean tagsCompatible(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        for (String x : a) {
            for (String y : b) {
                if (!x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x))) return true;
            }
        }
        return false;
    }

    private static boolean venuesOverlap(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return false;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        for (int i = 0; i + 2 <= shorter.length(); i++) {
            if (longer.contains(shorter.substring(i, i + 2))) return true;
        }
        return false;
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
