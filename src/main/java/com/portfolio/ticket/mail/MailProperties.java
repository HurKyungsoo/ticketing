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
}
