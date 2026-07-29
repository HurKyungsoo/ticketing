package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS(공연예술통합전산망) 클라이언트.
 *
 * 목록(pblprfr)에는 dtguidance(요일별 실제 공연시간)/pcseguidance(등급별 실제 가격)가
 * 없어서, 목록으로 받은 mt20id 로 상세(pblprfr/{mt20id})를 건별로 한 번 더 호출해 채운다.
 * 상세 호출이 실패해도 목록 자체는 버리지 않고, 그 건만 기본값(회차/등급가격 없음)으로 둔다.
 *
 * 객석수(totalSeatCount)는 KOPIS 목록/상세(pblprfr) 어디에도 없어서, 상세에 같이 오는
 * 시설 ID(mt10id)/홀 ID(mt13id)로 공연시설상세정보(prfplc/{mt10id})를 한 번 더 호출해 채운다.
 * 같은 공연장이 여러 공연에 반복 등장하므로 시설 단위로 캐시해 중복 호출을 막는다.
 * 그래도 못 채우면(호출 실패, 시설 정보 없음 등) venue-layouts.yml 에 공연장명이 없는 경우와
 * 마찬가지로 SeatGenerator 기본값(200석)이 적용된다.
 *
 * 주의: 인증키는 data.go.kr 이 아니라 www.kopis.or.kr 에서 별도 발급한다.
 *       정상/오류 응답 모두 XML 이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KopisPerformanceClient {

    private static final String SOURCE_PREFIX = "KOPIS-";
    private static final DateTimeFormatter DATE_PARAM_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 상세 API 를 지연 없이 연속 호출하면 KOPIS WAF 가 봇 트래픽으로 보고 전건을
     * "400 Request Blocked" 로 막는다 (정상/오류 KOPIS XML 이 아니라 WAF 자체 HTML 응답).
     * 건별 호출 사이에 최소한의 지연을 둬서 이를 피한다.
     */
    private static final long DETAIL_CALL_DELAY_MS = 150;

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Map<Character, DayOfWeek> DAY_CHAR = Map.of(
            '월', DayOfWeek.MONDAY, '화', DayOfWeek.TUESDAY, '수', DayOfWeek.WEDNESDAY,
            '목', DayOfWeek.THURSDAY, '금', DayOfWeek.FRIDAY, '토', DayOfWeek.SATURDAY, '일', DayOfWeek.SUNDAY);

    /**
     * 실제 dtguidance 예: "화요일(19:30), 금요일(19:30)" / "토요일(11:00,14:00)".
     * 반드시 "요일" 전체를 붙여서 찾는다 — "화요일"의 "일"만 보면 일요일로 오매칭된다.
     */
    private static final Pattern DAY_TIME_PATTERN =
            Pattern.compile("(월|화|수|목|금|토|일)요일\\s*\\(([^)]*)\\)");
    /** "목요일 ~ 금요일(10:30)" 같은 요일 범위. */
    private static final Pattern DAY_RANGE_TIME_PATTERN =
            Pattern.compile("(월|화|수|목|금|토|일)요일\\s*~\\s*(월|화|수|목|금|토|일)요일\\s*\\(([^)]*)\\)");
    private static final Pattern EVERYDAY_TIME_PATTERN = Pattern.compile("매일\\s*\\(([^)]*)\\)");

    /** "VIP석 100,000원" / "R 80000원" 처럼 등급 문자 뒤에 붙는 가격을 뽑는다. B 는 SeatGrade 에 없어 버린다. */
    private static final Pattern GRADE_PRICE_PATTERN =
            Pattern.compile("(VIP|R|S|A|B)\\s*석?\\s*([\\d,]{3,})\\s*원");

    private final RestClient publicDataRestClient;
    private final PublicDataProperties properties;
    private final PublicDataParser parser;
    private final XmlMapper xmlMapper = new XmlMapper();

    /** 시설/홀 ID -> 좌석수. 앱 기동 내내 유지 — 같은 공연장이 배치마다 계속 재등장하므로 유효하다. */
    private final Map<String, Integer> facilitySeatCountCache = new ConcurrentHashMap<>();

    public List<ExternalPerformance> fetchPage(int pageNo) {
        List<ExternalPerformance> basics;
        try {
            byte[] body = publicDataRestClient.get().uri(listUri(pageNo)).retrieve().body(byte[].class);
            basics = parseList(body);
        } catch (Exception e) {
            log.warn("KOPIS 목록 API 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return List.of();
        }

        List<ExternalPerformance> result = new ArrayList<>(basics.size());
        for (ExternalPerformance basic : basics) {
            result.add(enrichWithDetail(basic));
            sleepBetweenDetailCalls();
        }
        return result;
    }

    private void sleepBetweenDetailCalls() {
        try {
            Thread.sleep(DETAIL_CALL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private URI listUri(int pageNo) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusYears(1);

        return UriComponentsBuilder.fromUriString(properties.getKopisUrl())
                .queryParam("service", properties.getKopisServiceKey())
                .queryParam("stdate", from.format(DATE_PARAM_FORMAT))
                .queryParam("eddate", to.format(DATE_PARAM_FORMAT))
                .queryParam("cpage", pageNo)
                .queryParam("rows", properties.getSyncPageSize())
                .build(true)
                .toUri();
    }

    private List<ExternalPerformance> parseList(byte[] body) throws Exception {
        if (body == null || body.length == 0) return List.of();

        JsonNode root = xmlMapper.readTree(body);
        JsonNode items = root.path("db");
        if (items.isMissingNode()) {
            log.warn("KOPIS 목록 응답에 db 노드 없음 (인증키 오류 가능성): {}", preview(body));
            return List.of();
        }

        List<JsonNode> itemNodes = items.isArray() ? toList(items) : List.of(items);
        if (isErrorResponse(itemNodes)) return List.of();

        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            ExternalPerformance parsed = ExternalPerformance.builder()
                    .externalId(SOURCE_PREFIX + parser.text(item, "mt20id"))
                    .sourceType(SourceType.KOPIS)
                    .title(parser.text(item, "prfnm"))
                    .genre(parser.text(item, "genrenm"))
                    .venue(parser.text(item, "fcltynm"))
                    .region(parser.text(item, "area"))
                    .startDate(parser.date(item, "prfpdfrom"))
                    .endDate(parser.date(item, "prfpdto"))
                    .posterUrl(parser.text(item, "poster"))
                    .build();

            if (parsed.isValid()) {
                result.add(parsed);
            }
        }
        return result;
    }

    /**
     * KOPIS 는 인증키 오류도 정상 응답과 똑같은 {@code <dbs><db>} 구조로 내려준다.
     * (예: {@code <db><returncode>02</returncode><errmsg>SERVICE KEY IS NOT REGISTERED ERROR</errmsg></db>})
     * 그래서 db 노드 유무만 보면 인증 실패가 "0건 수집" 으로 조용히 묻힌다. errmsg 로 구분한다.
     */
    private boolean isErrorResponse(List<JsonNode> itemNodes) {
        if (itemNodes.isEmpty()) return false;

        JsonNode first = itemNodes.get(0);
        String errmsg = parser.text(first, "errmsg");
        if (errmsg == null) return false;

        log.warn("KOPIS 목록 API 오류 응답. returncode={}, errmsg={}",
                parser.text(first, "returncode"), errmsg);
        return true;
    }

    /** 상세 호출/파싱 실패는 이 건만 목록 정보로 대체한다. 배치 전체를 막지 않는다. */
    private ExternalPerformance enrichWithDetail(ExternalPerformance basic) {
        String mt20id = basic.getExternalId().substring(SOURCE_PREFIX.length());
        URI uri = UriComponentsBuilder.fromUriString(properties.getKopisUrl() + "/" + mt20id)
                .queryParam("service", properties.getKopisServiceKey())
                .build(true)
                .toUri();

        try {
            byte[] body = publicDataRestClient.get().uri(uri).retrieve().body(byte[].class);
            return applyDetail(basic, body);
        } catch (Exception e) {
            log.warn("KOPIS 상세 API 호출 실패. mt20id={}, msg={}", mt20id, e.getMessage());
            return basic;
        }
    }

    private ExternalPerformance applyDetail(ExternalPerformance basic, byte[] body) {
        if (body == null || body.length == 0) return basic;

        try {
            JsonNode root = xmlMapper.readTree(body);
            JsonNode item = root.path("db");
            if (item.isArray()) item = item.get(0);
            if (item == null || item.isMissingNode()) return basic;

            String area = parser.text(item, "area");
            String pcsGuidance = parser.text(item, "pcseguidance");
            Integer seatCount = resolveSeatCount(item);

            return ExternalPerformance.builder()
                    .externalId(basic.getExternalId())
                    .sourceType(basic.getSourceType())
                    .title(basic.getTitle())
                    .genre(basic.getGenre())
                    .venue(basic.getVenue())
                    .address(area != null ? area : basic.getAddress())
                    .region(area != null ? area : basic.getRegion())
                    .latitude(basic.getLatitude())
                    .longitude(basic.getLongitude())
                    .startDate(basic.getStartDate())
                    .endDate(basic.getEndDate())
                    .posterUrl(basic.getPosterUrl())
                    .description(parser.description(item, "sty"))
                    .ageLimit(parser.text(item, "prfage"))
                    .runningTime(parser.text(item, "prfruntime"))
                    .castMembers(truncate(parser.text(item, "prfcast"), 500))
                    .totalSeatCount(seatCount != null ? seatCount : basic.getTotalSeatCount())
                    .basePrice(pcsGuidance != null ? parser.price(item, "pcseguidance") : basic.getBasePrice())
                    .showTimesByDay(parseDtGuidance(parser.text(item, "dtguidance")))
                    .pricesByGrade(parsePcseGuidance(pcsGuidance))
                    .build();
        } catch (Exception e) {
            log.debug("KOPIS 상세 파싱 실패, 목록 정보만 사용. mt20id={}, msg={}", basic.getExternalId(), e.getMessage());
            return basic;
        }
    }

    /**
     * 상세 응답의 mt10id(시설)/mt13id(홀)로 공연시설상세정보를 조회해 실제 좌석수를 얻는다.
     * 캐시에 없을 때만 새로 호출하고, 그때만 호출 간 지연을 적용한다 (캐시 히트는 지연 없이 즉시 반환).
     * 실패하면 null — 호출부가 기존 로직(venue-layouts.yml 또는 기본값 200석)으로 대체한다.
     */
    private Integer resolveSeatCount(JsonNode item) {
        String mt10id = parser.text(item, "mt10id");
        if (mt10id == null) return null;
        String mt13id = parser.text(item, "mt13id");
        String cacheKey = mt13id != null ? mt13id : mt10id;

        Integer cached = facilitySeatCountCache.get(cacheKey);
        if (cached != null) return cached;

        Integer seatCount = fetchFacilitySeatCount(mt10id, mt13id);
        if (seatCount != null) {
            facilitySeatCountCache.put(cacheKey, seatCount);
            sleepBetweenDetailCalls();
        }
        return seatCount;
    }

    private Integer fetchFacilitySeatCount(String mt10id, String mt13id) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getKopisFacilityUrl() + "/" + mt10id)
                .queryParam("service", properties.getKopisServiceKey())
                .build(true)
                .toUri();

        try {
            byte[] body = publicDataRestClient.get().uri(uri).retrieve().body(byte[].class);
            return parseFacilitySeatCount(body, mt13id);
        } catch (Exception e) {
            log.debug("KOPIS 공연시설 조회 실패. mt10id={}, msg={}", mt10id, e.getMessage());
            return null;
        }
    }

    /** 다관 시설이면 mt13id 에 해당하는 홀의 좌석수를 우선 쓰고, 못 찾으면 시설 전체 좌석수로 대체한다. */
    private Integer parseFacilitySeatCount(byte[] body, String mt13id) {
        if (body == null || body.length == 0) return null;

        try {
            JsonNode root = xmlMapper.readTree(body);
            JsonNode item = root.path("db");
            if (item.isArray()) item = item.get(0);
            if (item == null || item.isMissingNode()) return null;

            if (mt13id != null) {
                JsonNode halls = item.path("mt13s").path("mt13");
                List<JsonNode> hallList = halls.isMissingNode() ? List.of() : (halls.isArray() ? toList(halls) : List.of(halls));
                for (JsonNode hall : hallList) {
                    if (mt13id.equals(parser.text(hall, "mt13id"))) {
                        Integer hallSeats = parser.number(hall, "seatscale");
                        if (hallSeats != null) return hallSeats;
                    }
                }
            }
            return parser.number(item, "seatscale");
        } catch (Exception e) {
            log.debug("KOPIS 공연시설 응답 파싱 실패. msg={}", e.getMessage());
            return null;
        }
    }

    /**
     * 실제 dtguidance 예: "화요일(19:30), 금요일(19:30)" / "토요일(11:00,14:00)" /
     * "목요일 ~ 금요일(10:30)" / "매일(19:00)". 세 패턴을 각각 매칭하고 요일별로 시각을
     * Set 에 모아 중복을 제거한다 — 범위 매칭과 단일 매칭이 같은 요일을 중복으로 잡을 수 있어서
     * (예: "목요일 ~ 금요일(10:30)"의 금요일이 단일 패턴에도 잡힘) 중복 제거가 꼭 필요하다.
     * (안 하면 같은 회차가 두 번 만들어지려다 유니크 제약 위반으로 배치가 죽는다.)
     * 요일/시각을 하나도 못 읽으면 null 을 반환해 호출부가 기존 8일 고정 규칙으로 대체하게 한다.
     */
    private Map<DayOfWeek, List<LocalTime>> parseDtGuidance(String raw) {
        if (raw == null || raw.isBlank()) return null;

        Map<DayOfWeek, Set<LocalTime>> byDay = new EnumMap<>(DayOfWeek.class);

        Matcher everyday = EVERYDAY_TIME_PATTERN.matcher(raw);
        while (everyday.find()) {
            addTimes(byDay, EnumSet.allOf(DayOfWeek.class), extractTimes(everyday.group(1)));
        }

        Matcher range = DAY_RANGE_TIME_PATTERN.matcher(raw);
        while (range.find()) {
            addTimes(byDay, expandRange(range.group(1).charAt(0), range.group(2).charAt(0)),
                    extractTimes(range.group(3)));
        }

        Matcher single = DAY_TIME_PATTERN.matcher(raw);
        while (single.find()) {
            DayOfWeek day = DAY_CHAR.get(single.group(1).charAt(0));
            if (day != null) {
                addTimes(byDay, EnumSet.of(day), extractTimes(single.group(2)));
            }
        }

        if (byDay.isEmpty()) return null;
        Map<DayOfWeek, List<LocalTime>> result = new EnumMap<>(DayOfWeek.class);
        byDay.forEach((day, times) -> result.put(day, new ArrayList<>(times)));
        return result;
    }

    private void addTimes(Map<DayOfWeek, Set<LocalTime>> byDay, Set<DayOfWeek> days, List<LocalTime> times) {
        if (times.isEmpty()) return;
        for (DayOfWeek day : days) {
            byDay.computeIfAbsent(day, d -> new LinkedHashSet<>()).addAll(times);
        }
    }

    /** 월~일 순서가 DayOfWeek 순서와 같다는 걸 이용해 범위를 펼친다. 역방향(토~월 등)은 지원하지 않고 건너뛴다. */
    private Set<DayOfWeek> expandRange(char fromChar, char toChar) {
        DayOfWeek from = DAY_CHAR.get(fromChar);
        DayOfWeek to = DAY_CHAR.get(toChar);
        if (from == null || to == null || from.getValue() > to.getValue()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (int v = from.getValue(); v <= to.getValue(); v++) {
            days.add(DayOfWeek.of(v));
        }
        return days;
    }

    private List<LocalTime> extractTimes(String segment) {
        List<LocalTime> times = new ArrayList<>();
        Matcher matcher = TIME_PATTERN.matcher(segment);
        while (matcher.find()) {
            try {
                times.add(LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
            } catch (Exception ignored) {
                // 24:00 등 범위를 벗어나는 값은 건너뛰고 다음 매치를 시도한다.
            }
        }
        return times;
    }

    /**
     * "VIP석 100,000원 R석 80,000원 S석 60,000원" 같은 자유 텍스트를 등급별로 파싱한다.
     * 등급을 하나도 못 읽으면 null 을 반환해 호출부가 basePrice 비율 계산으로 대체하게 한다.
     */
    private Map<SeatGrade, Integer> parsePcseGuidance(String raw) {
        if (raw == null || raw.isBlank()) return null;

        Map<SeatGrade, Integer> result = new EnumMap<>(SeatGrade.class);
        Matcher matcher = GRADE_PRICE_PATTERN.matcher(raw);
        while (matcher.find()) {
            String gradeToken = matcher.group(1);
            if ("B".equals(gradeToken)) continue;

            try {
                SeatGrade grade = SeatGrade.valueOf(gradeToken);
                int price = Integer.parseInt(matcher.group(2).replace(",", ""));
                result.put(grade, price);
            } catch (Exception ignored) {
                // 다음 매치를 시도한다.
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 컬럼 길이(500)를 넘는 prfcast(캐스팅 교체가 잦은 공연은 매우 길 수 있음)를 안전하게 자른다. */
    private String truncate(String raw, int maxLength) {
        if (raw == null || raw.length() <= maxLength) return raw;
        return raw.substring(0, maxLength);
    }

    private List<JsonNode> toList(JsonNode arrayNode) {
        List<JsonNode> list = new ArrayList<>();
        arrayNode.forEach(list::add);
        return list;
    }

    /** 경고 로그용으로만 쓴다 — 응답이 UTF-8 XML 이라는 전제로 앞부분만 잘라 보여준다. */
    private String preview(byte[] body) {
        return new String(body, 0, Math.min(body.length, 200), java.nio.charset.StandardCharsets.UTF_8);
    }
}
