package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.portfolio.ticket.domain.SeatGrade;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS(공연예술통합전산망) 클라이언트.
 *
 * 목록(pblprfr)에는 dtguidance(요일별 실제 공연시간)/pcseguidance(등급별 실제 가격)가
 * 없어서, 목록으로 받은 mt20id 로 상세(pblprfr/{mt20id})를 건별로 한 번 더 호출해 채운다.
 * 상세 호출이 실패해도 목록 자체는 버리지 않고, 그 건만 기본값(회차/등급가격 없음)으로 둔다.
 *
 * 객석수(totalSeatCount)는 KOPIS 목록/상세 어디에도 없다 (공연시설상세정보 API 로 별도 조회해야
 * 하며 이번 연동 범위 밖). venue-layouts.yml 에 공연장명이 없으면 SeatGenerator 기본값(200석)이 적용된다.
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

    public List<ExternalPerformance> fetchPage(int pageNo) {
        List<ExternalPerformance> basics;
        try {
            String body = publicDataRestClient.get().uri(listUri(pageNo)).retrieve().body(String.class);
            basics = parseList(body);
        } catch (Exception e) {
            log.warn("KOPIS 목록 API 호출 실패. pageNo={}, msg={}", pageNo, e.getMessage());
            return List.of();
        }

        List<ExternalPerformance> result = new ArrayList<>(basics.size());
        for (ExternalPerformance basic : basics) {
            result.add(enrichWithDetail(basic));
        }
        return result;
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

    private List<ExternalPerformance> parseList(String body) throws Exception {
        if (body == null || body.isBlank()) return List.of();
        if (!body.stripLeading().startsWith("<")) {
            log.warn("KOPIS 목록 응답이 XML 이 아님: {}", body.substring(0, Math.min(200, body.length())));
            return List.of();
        }

        JsonNode root = xmlMapper.readTree(body.getBytes());
        JsonNode items = root.path("db");
        if (items.isMissingNode()) {
            log.warn("KOPIS 목록 응답에 db 노드 없음 (인증키 오류 가능성): {}",
                    body.substring(0, Math.min(200, body.length())));
            return List.of();
        }

        List<JsonNode> itemNodes = items.isArray() ? toList(items) : List.of(items);
        List<ExternalPerformance> result = new ArrayList<>();
        for (JsonNode item : itemNodes) {
            ExternalPerformance parsed = ExternalPerformance.builder()
                    .externalId(SOURCE_PREFIX + parser.text(item, "mt20id"))
                    .title(parser.text(item, "prfnm"))
                    .genre(parser.text(item, "genrenm"))
                    .venue(parser.text(item, "fcltynm"))
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

    /** 상세 호출/파싱 실패는 이 건만 목록 정보로 대체한다. 배치 전체를 막지 않는다. */
    private ExternalPerformance enrichWithDetail(ExternalPerformance basic) {
        String mt20id = basic.getExternalId().substring(SOURCE_PREFIX.length());
        URI uri = UriComponentsBuilder.fromUriString(properties.getKopisUrl() + "/" + mt20id)
                .queryParam("service", properties.getKopisServiceKey())
                .build(true)
                .toUri();

        try {
            String body = publicDataRestClient.get().uri(uri).retrieve().body(String.class);
            return applyDetail(basic, body);
        } catch (Exception e) {
            log.warn("KOPIS 상세 API 호출 실패. mt20id={}, msg={}", mt20id, e.getMessage());
            return basic;
        }
    }

    private ExternalPerformance applyDetail(ExternalPerformance basic, String body) {
        if (body == null || body.isBlank()) return basic;

        try {
            JsonNode root = xmlMapper.readTree(body.getBytes());
            JsonNode item = root.path("db");
            if (item.isArray()) item = item.get(0);
            if (item == null || item.isMissingNode()) return basic;

            String area = parser.text(item, "area");
            String pcsGuidance = parser.text(item, "pcseguidance");

            return ExternalPerformance.builder()
                    .externalId(basic.getExternalId())
                    .title(basic.getTitle())
                    .genre(basic.getGenre())
                    .venue(basic.getVenue())
                    .address(area != null ? area : basic.getAddress())
                    .latitude(basic.getLatitude())
                    .longitude(basic.getLongitude())
                    .startDate(basic.getStartDate())
                    .endDate(basic.getEndDate())
                    .posterUrl(basic.getPosterUrl())
                    .totalSeatCount(basic.getTotalSeatCount())
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

    private List<JsonNode> toList(JsonNode arrayNode) {
        List<JsonNode> list = new ArrayList<>();
        arrayNode.forEach(list::add);
        return list;
    }
}
