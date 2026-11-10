package com.portfolio.ticket.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.portfolio.ticket.domain.BookingLink;
import com.portfolio.ticket.domain.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
     * 호출이 몰리면 KOPIS WAF 가 "400 Request Blocked" 로 막는다 (KOPIS XML 이 아니라 WAF 자체 HTML 응답).
     * 운영 서버 실측: 상세+시설을 초당 약 6건 호출하면 3초 안에 막혔다. 간격만으로 완전히 피할 수는 없어서,
     * 600ms 간격에서도 호출이 누적되면 60건 중 13건이 막혔다. 다만 13건 모두 같은 호출을 5~10초 뒤 다시
     * 보내면 통과했다. 그래서 간격은 몰림을 줄이는 용도로 두고, 막히면 기다렸다 재시도하는 쪽을 핵심으로 삼는다.
     * 막힌 채 바로 다음 호출로 넘어가던 이전 방식은 그 회차의 상세 호출이 거의 전부 실패했다.
     */
    private static final String WAF_BLOCK_MARKER = "Request Blocked";
    private static final int MAX_BLOCK_RETRIES = 3;

    private final RestClient publicDataRestClient;
    private final PublicDataProperties properties;
    private final PublicDataParser parser;
    private final XmlMapper xmlMapper = new XmlMapper();

    /** 시설/홀 ID -> 시설 정보. 앱 기동 내내 유지 — 같은 공연장이 배치마다 계속 재등장하므로 유효하다. */
    private final Map<String, FacilityInfo> facilityCache = new ConcurrentHashMap<>();

    private long lastCallNanos;

    /**
     * @param afterDate 이 날짜 이후 등록/수정된 항목만 받는다(증분 수집). null 이면 전체.
     *                  목록 1건마다 상세를 또 호출하는 구조라, 증분이면 상세 호출 수가 그만큼 줄어든다.
     * @throws KopisFetchException 목록을 받지 못했거나 WAF 차단이 풀리지 않았을 때.
     *         빈 목록은 "받을 게 없음"만 뜻한다 — 실패까지 빈 목록으로 돌려주면 수집이 성공으로 기록되어
     *         증분 기준일이 앞으로 가고, 그 구간의 공연을 영영 놓친다.
     */
    public List<ExternalPerformance> fetchPage(int pageNo, LocalDate afterDate) {
        List<ExternalPerformance> basics;
        try {
            basics = parseList(call(listUri(pageNo, afterDate)));
        } catch (KopisFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new KopisFetchException("KOPIS 목록 API 호출 실패. pageNo=" + pageNo + ", msg=" + e.getMessage(), e);
        }

        List<ExternalPerformance> result = new ArrayList<>(basics.size());
        for (ExternalPerformance basic : basics) {
            result.add(enrichWithDetail(basic));
        }
        return result;
    }

    /** 모든 KOPIS 호출이 지나는 길. 호출 간격을 지키고, WAF 에 막히면 풀릴 때까지 기다렸다 다시 시도한다. */
    private byte[] call(URI uri) {
        for (int retry = 0; ; retry++) {
            waitForCallSlot();
            try {
                return publicDataRestClient.get().uri(uri).retrieve().body(byte[].class);
            } catch (HttpClientErrorException e) {
                if (!e.getResponseBodyAsString().contains(WAF_BLOCK_MARKER)) {
                    throw e;
                }
                if (retry >= MAX_BLOCK_RETRIES) {
                    throw new KopisFetchException("KOPIS WAF 차단이 풀리지 않아 수집을 중단합니다.", e);
                }
                log.warn("KOPIS WAF 에 막혔습니다. {}ms 후 다시 시도합니다.", properties.getKopisBlockWaitMs());
                sleep(properties.getKopisBlockWaitMs());
            }
        }
    }

    private synchronized void waitForCallSlot() {
        long elapsedMs = (System.nanoTime() - lastCallNanos) / 1_000_000;
        if (lastCallNanos != 0 && elapsedMs < properties.getKopisMinCallIntervalMs()) {
            sleep(properties.getKopisMinCallIntervalMs() - elapsedMs);
        }
        lastCallNanos = System.nanoTime();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private URI listUri(int pageNo, LocalDate afterDate) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusYears(1);

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.getKopisUrl())
                .queryParam("service", properties.getKopisServiceKey())
                .queryParam("stdate", from.format(DATE_PARAM_FORMAT))
                .queryParam("eddate", to.format(DATE_PARAM_FORMAT))
                .queryParam("cpage", pageNo)
                .queryParam("rows", properties.getSyncPageSize());

        if (afterDate != null) {
            builder.queryParam("afterdate", afterDate.format(DATE_PARAM_FORMAT));
        }
        return builder.build(true).toUri();
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
        if (isErrorResponse(itemNodes)) {
            throw new KopisFetchException("KOPIS 목록 API 가 오류를 응답했습니다.");
        }

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

    /**
     * 이미 저장된 공연의 상세를 다시 받는다. 수집 때 상세를 놓친 공연을 채우는 데 쓴다.
     *
     * @return 응답을 받지 못했으면(차단이 아닌 HTTP 오류 등) 빈 값. 다음에 다시 시도한다.
     * @throws KopisFetchException WAF 차단이 풀리지 않았을 때. 남은 공연도 막힐 것이므로 멈춘다.
     */
    public Optional<ExternalPerformance> fetchDetail(ExternalPerformance basic) {
        String mt20id = basic.getExternalId().substring(SOURCE_PREFIX.length());
        byte[] body;
        try {
            body = call(detailUri(mt20id));
        } catch (KopisFetchException e) {
            throw e;
        } catch (Exception e) {
            log.warn("KOPIS 상세 API 호출 실패. mt20id={}, msg={}", mt20id, e.getMessage());
            return Optional.empty();
        }
        return Optional.of(applyDetail(basic, body));
    }

    private URI detailUri(String mt20id) {
        return UriComponentsBuilder.fromUriString(properties.getKopisUrl() + "/" + mt20id)
                .queryParam("service", properties.getKopisServiceKey())
                .build(true)
                .toUri();
    }

    /** 상세 호출/파싱 실패는 이 건만 목록 정보로 대체한다. 배치 전체를 막지 않는다. */
    private ExternalPerformance enrichWithDetail(ExternalPerformance basic) {
        String mt20id = basic.getExternalId().substring(SOURCE_PREFIX.length());
        URI uri = detailUri(mt20id);

        try {
            return applyDetail(basic, call(uri));
        } catch (KopisFetchException e) {
            throw e;
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
            FacilityInfo facility = resolveFacility(item);

            // region 은 지역 필터용이라 시도명(area)을 그대로 두고, address 만 시설의 도로명 주소로 승격한다.
            String address = facility != null && facility.address() != null
                    ? facility.address()
                    : (area != null ? area : basic.getAddress());

            return ExternalPerformance.builder()
                    .externalId(basic.getExternalId())
                    .sourceType(basic.getSourceType())
                    .title(basic.getTitle())
                    .genre(basic.getGenre())
                    .venue(basic.getVenue())
                    // 홀 ID 는 좌석 배치를 홀 단위로 매칭하기 위해 남긴다.
                    // 공연장명(venue)은 시설 이름이라 다관 시설을 구분하지 못한다.
                    .venueFacilityId(parser.text(item, "mt10id"))
                    .venueHallId(parser.text(item, "mt13id"))
                    .address(address)
                    .region(area != null ? area : basic.getRegion())
                    .latitude(facility != null ? facility.latitude() : basic.getLatitude())
                    .longitude(facility != null ? facility.longitude() : basic.getLongitude())
                    .startDate(basic.getStartDate())
                    .endDate(basic.getEndDate())
                    .posterUrl(basic.getPosterUrl())
                    .description(parser.description(item, "sty"))
                    .ageLimit(parser.text(item, "prfage"))
                    .runningTime(parser.text(item, "prfruntime"))
                    .castMembers(truncate(parser.text(item, "prfcast"), 500))
                    .totalSeatCount(facility != null && facility.seatCount() != null
                            ? facility.seatCount() : basic.getTotalSeatCount())
                    .basePrice(pcsGuidance != null ? parser.price(item, "pcseguidance") : basic.getBasePrice())
                    .showTimesByDay(KopisGuidanceParser.showTimes(parser.text(item, "dtguidance")))
                    .pricesByGrade(KopisGuidanceParser.pricesByGrade(pcsGuidance))
                    .showTimeGuidance(parser.text(item, "dtguidance"))
                    .priceGuidance(pcsGuidance)
                    .bookingLinks(parseRelates(item))
                    .build();
        } catch (Exception e) {
            log.debug("KOPIS 상세 파싱 실패, 목록 정보만 사용. mt20id={}, msg={}", basic.getExternalId(), e.getMessage());
            return basic;
        }
    }

    /**
     * 공연시설상세정보에서 뽑아 쓰는 값들. 좌석수만 홀(mt13id) 단위이고,
     * 위경도/주소는 시설(mt10id) 단위라 같은 시설의 어느 홀이든 같은 값이다.
     */
    private record FacilityInfo(Integer seatCount, Double latitude, Double longitude, String address) {

        boolean isEmpty() {
            return seatCount == null && latitude == null && longitude == null && address == null;
        }
    }

    /**
     * 상세 응답의 mt10id(시설)/mt13id(홀)로 공연시설상세정보를 조회한다.
     * KOPIS 목록/상세에는 좌표가 없고 이 응답에만 la/lo/adres 가 있어서, 좌석수와 함께 여기서 같이 가져온다.
     * 캐시에 없을 때만 새로 호출한다 (캐시 히트는지연 없이 즉시 반환).
     * 실패하면 null — 호출부가 기존 로직(venue-layouts.yml 또는 기본값 200석, area 주소)으로 대체한다.
     */
    private FacilityInfo resolveFacility(JsonNode item) {
        String mt10id = parser.text(item, "mt10id");
        if (mt10id == null) return null;
        String mt13id = parser.text(item, "mt13id");
        String cacheKey = mt13id != null ? mt13id : mt10id;

        FacilityInfo cached = facilityCache.get(cacheKey);
        if (cached != null) return cached;

        FacilityInfo facility = fetchFacility(mt10id, mt13id);
        if (facility != null) {
            facilityCache.put(cacheKey, facility);
        }
        return facility;
    }

    private FacilityInfo fetchFacility(String mt10id, String mt13id) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getKopisFacilityUrl() + "/" + mt10id)
                .queryParam("service", properties.getKopisServiceKey())
                .build(true)
                .toUri();

        try {
            return parseFacility(call(uri), mt13id);
        } catch (KopisFetchException e) {
            throw e;
        } catch (Exception e) {
            log.debug("KOPIS 공연시설 조회 실패. mt10id={}, msg={}", mt10id, e.getMessage());
            return null;
        }
    }

    /** 다관 시설이면 mt13id 에 해당하는 홀의 좌석수를 우선 쓰고, 못 찾으면 시설 전체 좌석수로 대체한다. */
    private FacilityInfo parseFacility(byte[] body, String mt13id) {
        if (body == null || body.length == 0) return null;

        try {
            JsonNode root = xmlMapper.readTree(body);
            JsonNode item = root.path("db");
            if (item.isArray()) item = item.get(0);
            if (item == null || item.isMissingNode()) return null;

            FacilityInfo facility = new FacilityInfo(
                    resolveSeatScale(item, mt13id),
                    parser.decimal(item, "la"),
                    parser.decimal(item, "lo"),
                    parser.text(item, "adres"));

            // 전부 비어 있으면 캐시에 담아둘 이유가 없다. null 을 돌려 다음 기회에 다시 시도하게 둔다.
            return facility.isEmpty() ? null : facility;
        } catch (Exception e) {
            log.debug("KOPIS 공연시설 응답 파싱 실패. msg={}", e.getMessage());
            return null;
        }
    }

    private Integer resolveSeatScale(JsonNode item, String mt13id) {
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
    }

    /**
     * 상세 응답의 relates(실제 예매처 목록). 하나면 relate 가 단일 노드, 여럿이면 배열로
     * 온다 — mt13s.mt13 과 같은 이유로 toList() 로 통일한다. 이름·URL 중 하나라도 비면
     * 그 건만 버린다(그 예매처 한 곳만 안내가 어긋날 뿐, 나머지 예매처까지 놓칠 이유는 없다).
     */
    private List<BookingLink> parseRelates(JsonNode item) {
        JsonNode relates = item.path("relates").path("relate");
        if (relates.isMissingNode()) return List.of();

        List<JsonNode> relateNodes = relates.isArray() ? toList(relates) : List.of(relates);
        List<BookingLink> result = new ArrayList<>();
        for (JsonNode relate : relateNodes) {
            String name = parser.text(relate, "relatenm");
            String url = parser.text(relate, "relateurl");
            if (name != null && url != null) {
                result.add(new BookingLink(name, url));
            }
        }
        return result;
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
