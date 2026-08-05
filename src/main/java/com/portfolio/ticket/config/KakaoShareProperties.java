package com.portfolio.ticket.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 카카오톡 공유하기 JS SDK 설정. 공연 상세의 "카카오톡 공유" 버튼에 쓴다.
 *
 * <p>OAuth 로그인({@code KAKAO_CLIENT_ID})과는 다른 키다 — 그건 REST API 키고, 이건
 * 카카오 개발자 콘솔의 "JavaScript 키"다. 키가 없으면(미발급 상태) 버튼을 아예 렌더링하지
 * 않는다 — 네이버 지도와 같은 이유로, 키 없이 SDK 를 초기화하면 호출이 전부 실패한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kakao.share")
public class KakaoShareProperties {

    private static final String UNSET = "REPLACE_ME";

    private String jsKey;

    public boolean isConfigured() {
        return jsKey != null && !jsKey.isBlank() && !UNSET.equals(jsKey);
    }
}
