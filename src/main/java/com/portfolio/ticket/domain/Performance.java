package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 공연. 공공데이터 API 로 수집되며 externalId 로 중복 수집을 막는다.
 */
@Entity
@Table(
    name = "performance",
    uniqueConstraints = @UniqueConstraint(name = "uk_performance_external", columnNames = "external_id"),
    indexes = @Index(name = "idx_performance_period", columnList = "startDate, endDate")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Performance {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 공공데이터 원본 식별자 (API 별 prefix + 원본 키) */
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    /** 어느 소스(KOPIS/STANDARD/CULTURE/SEED)에서 왔는지. 필터/카테고리 집계에 쓴다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20)
    private SourceType sourceType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String genre;

    /** genre 원본 문자열을 PerformanceCategoryResolver 로 정규화한 값. 카테고리 필터/탭에 쓴다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PerformanceCategory category;

    @Column(length = 200)
    private String venue;

    /**
     * KOPIS 공연시설 ID (mt10id, 예: "FC001231"). 시설은 "예술의전당" 같은 건물 단위다.
     * KOPIS 상세에서만 채워지고 다른 소스는 null.
     */
    @Column(name = "venue_facility_id", length = 30)
    private String venueFacilityId;

    /**
     * KOPIS 공연장 ID (mt13id, 예: "FC001231-01"). 시설 안의 개별 홀 단위다.
     *
     * venue(공연장명)는 시설 이름이라 다관 시설을 구분하지 못한다. "예술의전당" 하나에
     * 콘서트홀(2,505석)·오페라극장(2,283석)·CJ토월극장(1,004석)이 섞여 있어서,
     * 이름만으로 좌석 배치를 매칭하면 대부분 틀린 구조를 받게 된다.
     * 표기가 바뀌어도 안 깨지는 홀 단위 키가 필요해서 남긴다.
     */
    @Column(name = "venue_hall_id", length = 30)
    private String venueHallId;

    @Column(length = 300)
    private String address;

    /**
     * 시/도 단위 지역 필터용 (예: "서울특별시"). KOPIS 목록의 area 필드에서만 채워진다.
     * 표준데이터/문화정보는 지역 코드가 따로 없어 null — 지역 필터에서 "기타"로 묶인다.
     */
    @Column(length = 50)
    private String region;

    private Double latitude;
    private Double longitude;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(length = 500)
    private String posterUrl;

    @Column(length = 1000)
    private String description;

    /** KOPIS 상세(prfage). 표준데이터/문화정보에는 없어서 KOPIS 수집분만 채워진다. */
    @Column(length = 50)
    private String ageLimit;

    /** KOPIS 상세(prfruntime). 예: "1시간 30분". */
    @Column(length = 30)
    private String runningTime;

    /**
     * KOPIS 상세(prfcast). 배우 이름을 쉼표로 나열한 원본 그대로.
     * 필드명을 cast 로 두면 SQL 예약어(CAST 함수)와 충돌해 DDL/쿼리가 깨진다 — castMembers 로 회피.
     */
    @Column(length = 500)
    private String castMembers;

    /** API 가 준 객석수. 좌석 자동 생성의 기준값. */
    private Integer totalSeatCount;

    /** API 가 준 관람요금 문자열에서 파싱한 기준가 */
    private Integer basePrice;

    @Builder.Default
    @OneToMany(mappedBy = "performance", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PerformanceSchedule> schedules = new ArrayList<>();

    /**
     * 배치 재수집 시 변경분만 반영. category 는 매번 다시 계산해서 넣는다 — 예전에
     * sourceType/category 컬럼이 없던 시절 저장된 행(null)도 재동기화되면서 자연히 채워진다.
     */
    public void updateFromExternal(String title, String genre, String venue,
                                   String venueFacilityId, String venueHallId, String address,
                                   Double latitude, Double longitude,
                                   LocalDate startDate, LocalDate endDate,
                                   String posterUrl, String description,
                                   String ageLimit, String runningTime, String castMembers,
                                   Integer totalSeatCount, Integer basePrice,
                                   SourceType sourceType, PerformanceCategory category, String region) {
        this.title = title;
        this.genre = genre;
        this.venue = venue;
        this.address = address;
        // 홀/시설 ID 는 KOPIS 상세에서만 온다. 다른 소스로 갱신될 때 지워지지 않게 한다.
        if (venueFacilityId != null) this.venueFacilityId = venueFacilityId;
        if (venueHallId != null) this.venueHallId = venueHallId;
        // 좌표는 소스에 따라 없을 수 있다. null 로 덮어써서 이미 채워둔 값을 날리지 않는다.
        if (latitude != null) this.latitude = latitude;
        if (longitude != null) this.longitude = longitude;
        this.startDate = startDate;
        this.endDate = endDate;
        if (posterUrl != null) this.posterUrl = posterUrl;
        if (description != null) this.description = description;
        if (ageLimit != null) this.ageLimit = ageLimit;
        if (runningTime != null) this.runningTime = runningTime;
        if (castMembers != null) this.castMembers = castMembers;
        if (totalSeatCount != null) this.totalSeatCount = totalSeatCount;
        if (basePrice != null) this.basePrice = basePrice;
        this.sourceType = sourceType;
        this.category = category;
        this.region = region;
    }

    public void addSchedule(PerformanceSchedule schedule) {
        this.schedules.add(schedule);
        schedule.assignPerformance(this);
    }

    private static final String KOPIS_EXTERNAL_ID_PREFIX = "KOPIS-";

    /**
     * KOPIS 공식 상세 페이지로 연결할 mt20id. KOPIS 에서 수집된 공연만 값이 있고,
     * 표준데이터/문화정보/시드 공연은 KOPIS 에 대응 레코드가 없으므로 null.
     *
     * <p>이 사이트의 회차·좌석·가격은 {@code SeatGenerator} 가 만든 것이라 실제 예매처
     * 정보와 다르다 — 화면은 이 값이 있을 때 그 사실을 함께 알리고 공식 페이지로 안내한다.
     */
    public String kopisId() {
        if (sourceType != SourceType.KOPIS || externalId == null
                || !externalId.startsWith(KOPIS_EXTERNAL_ID_PREFIX)) {
            return null;
        }
        return externalId.substring(KOPIS_EXTERNAL_ID_PREFIX.length());
    }
}
