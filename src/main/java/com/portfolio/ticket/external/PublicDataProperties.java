package com.portfolio.ticket.external;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "publicdata")
public class PublicDataProperties {
    private String serviceKey;
    private String performanceUrl;
    private String cultureUrl;
    private int syncPageSize = 100;
    private int syncMaxPages = 5;

    /** KOPIS(공연예술통합전산망)는 data.go.kr 과 별도로 발급받는 인증키를 쓴다. */
    private String kopisServiceKey;
    private String kopisUrl;
    /** 공연시설상세정보(좌석수 등). /{mt10id} 를 붙여서 호출한다. */
    private String kopisFacilityUrl;
    /** KOPIS 호출 사이의 최소 간격. 상세·시설·목록 호출을 모두 합쳐 적용한다. */
    private long kopisMinCallIntervalMs = 600;
    /** KOPIS 방화벽에 막혔을 때 다시 시도하기 전 기다리는 시간. */
    private long kopisBlockWaitMs = 15_000;
    /** 수집 뒤 상세를 다시 받아 채울 공연 수의 하루 상한. 0 이면 끈다. */
    private int kopisDetailBackfillLimit = 300;
    /** 회차·좌석을 원문으로 다시 만들 공연 수의 하루 상한. 0 이면 끈다. */
    private int scheduleRebuildLimit = 100;
    /** 지난 회차 빈 좌석 정리에 하루 쓸 시간(분). 0 이면 끈다. */
    private long pastSeatCleanupMinutes = 15;
}
