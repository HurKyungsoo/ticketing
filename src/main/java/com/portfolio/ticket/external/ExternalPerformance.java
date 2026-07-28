package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SourceType;
import lombok.Builder;
import lombok.Getter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 세 API(전국공연행사정보표준데이터 / 한눈에보는문화정보 / KOPIS)의 응답을
 * 하나로 정규화한 중간 모델. 서비스 계층은 이 타입만 안다.
 */
@Getter
@Builder
public class ExternalPerformance {

    private String externalId;
    private SourceType sourceType;
    private String title;
    private String genre;
    private String venue;
    private String address;
    private Double latitude;
    private Double longitude;
    private LocalDate startDate;
    private LocalDate endDate;
    private String posterUrl;
    private Integer totalSeatCount;
    private Integer basePrice;

    /**
     * KOPIS dtguidance(요일별 실제 공연시간) 파싱 결과. 요일 → 그 요일의 공연 시각 목록.
     * null 이면 원본에 회차 개념이 없다는 뜻이고, 그때는 기존 8일치 고정 19시 규칙을 쓴다.
     */
    private Map<DayOfWeek, List<LocalTime>> showTimesByDay;

    /**
     * KOPIS pcseguidance(등급별 실제 가격) 파싱 결과. null 이면 basePrice 에
     * SeatGrade 별 비율(VIP 1.5/R 1.2/S 1.0/A 0.8)을 곱한 기존 방식을 쓴다.
     */
    private Map<SeatGrade, Integer> pricesByGrade;

    public boolean isValid() {
        return externalId != null && !externalId.isBlank()
                && title != null && !title.isBlank()
                && startDate != null && endDate != null;
    }
}
