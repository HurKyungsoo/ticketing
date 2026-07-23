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

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String genre;

    @Column(length = 200)
    private String venue;

    @Column(length = 300)
    private String address;

    private Double latitude;
    private Double longitude;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(length = 500)
    private String posterUrl;

    @Column(length = 1000)
    private String description;

    /** API 가 준 객석수. 좌석 자동 생성의 기준값. */
    private Integer totalSeatCount;

    /** API 가 준 관람요금 문자열에서 파싱한 기준가 */
    private Integer basePrice;

    @Builder.Default
    @OneToMany(mappedBy = "performance", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PerformanceSchedule> schedules = new ArrayList<>();

    /** 배치 재수집 시 변경분만 반영 */
    public void updateFromExternal(String title, String genre, String venue, String address,
                                   LocalDate startDate, LocalDate endDate,
                                   String posterUrl, Integer totalSeatCount, Integer basePrice) {
        this.title = title;
        this.genre = genre;
        this.venue = venue;
        this.address = address;
        this.startDate = startDate;
        this.endDate = endDate;
        if (posterUrl != null) this.posterUrl = posterUrl;
        if (totalSeatCount != null) this.totalSeatCount = totalSeatCount;
        if (basePrice != null) this.basePrice = basePrice;
    }

    public void addSchedule(PerformanceSchedule schedule) {
        this.schedules.add(schedule);
        schedule.assignPerformance(this);
    }
}
