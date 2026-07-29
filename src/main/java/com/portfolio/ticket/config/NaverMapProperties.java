package com.portfolio.ticket.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 네이버 지도 JS API 설정. 공연 상세의 공연장 위치 지도에 쓴다.
 *
 * 키가 없으면(미발급 상태) 지도를 아예 렌더링하지 않고 링크로만 대체한다 —
 * 키 없이 스크립트를 부르면 화면에 인증 실패 오버레이가 뜬다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "naver.map")
public class NaverMapProperties {

    private static final String UNSET = "REPLACE_ME";

    private String clientId;

    /** {@code ncpKeyId}(신규 콘솔) 또는 {@code ncpClientId}(기존 콘솔). application.yml 주석 참고. */
    private String keyParam = "ncpKeyId";

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && !UNSET.equals(clientId);
    }

    /** 지도 스크립트 URL. 키가 없으면 null 이라 템플릿이 지도를 건너뛴다. */
    public String scriptUrl() {
        if (!isConfigured()) return null;
        return "https://oapi.map.naver.com/openapi/v3/maps.js?" + keyParam + "=" + clientId;
    }
}
