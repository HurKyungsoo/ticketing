package com.portfolio.ticket.external;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 두 API(전국공연행사정보표준데이터 / 한눈에보는문화정보)의 응답을
 * 하나로 정규화한 중간 모델. 서비스 계층은 이 타입만 안다.
 */
@Getter
@Builder
public class ExternalPerformance {

    private String externalId;
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

    public boolean isValid() {
        return externalId != null && !externalId.isBlank()
                && title != null && !title.isBlank()
                && startDate != null && endDate != null;
    }
}
