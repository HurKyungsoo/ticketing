package com.portfolio.ticket.mail;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SMTP 접속 정보는 spring.mail.* 가 이미 다루므로, 여긴 발신자 표시값만 둔다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mail")
public class MailProperties {
    private String fromAddress;

    /**
     * 메일 본문 링크에 쓸 서비스 주소. 비밀번호 재설정은 요청을 처리하는 컨트롤러가
     * {@code ServletUriComponentsBuilder} 로 현재 주소를 뽑아 쓰지만, 예매 확인 메일은
     * <b>커밋 후 이벤트 리스너</b>에서 나가므로 요청 컨텍스트에 기대면 안 된다 — 토스 웹훅으로
     * 확정되는 경로도 있고, 지금은 아니어도 배치에서 보내게 되는 순간 조용히 깨진다.
     */
    private String baseUrl;
}
