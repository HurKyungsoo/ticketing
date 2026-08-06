package com.portfolio.ticket.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "toss")
public class TossProperties {
    private String clientKey;
    private String secretKey;
    private String apiBaseUrl;

    /**
     * 토스 결제창을 거치지 않고 「결제하기」를 누르는 즉시 예매를 확정하는 테스트 모드.
     *
     * <p>결제 이후 화면(완료·마이페이지·취소·환불)을 확인하려면 매번 토스 테스트 결제창을
     * 통과해야 하는데, 그러려면 발급받은 키가 환경변수에 있어야 한다. 키 없이 띄운
     * 로컬(기본값 {@code REPLACE_ME})에서는 결제 버튼이 아예 동작하지 않아서 결제 다음
     * 화면들을 전혀 볼 수 없었다.
     *
     * <p><b>기본값은 false 다.</b> 켜는 곳은 application.yml 의 local 프로파일 한 곳뿐이고,
     * 이 값이 true 일 때만 {@code MockPaymentController} 빈이 등록된다 — 런타임 분기가
     * 아니라 조건부 빈이라, 꺼져 있으면 확정 경로가 코드에 존재하지 않는 것과 같다(404).
     * prod 에서 실수로 켜지 않도록 값을 프로파일 밖에 두지 말 것.
     */
    private boolean mockEnabled = false;
}
