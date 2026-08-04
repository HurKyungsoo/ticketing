package com.portfolio.ticket.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 재설정 메일 발송. SMTP 가 설정되지 않은 환경(spring.mail.host 가 기본값
 * REPLACE_ME 인 로컬 등)에서는 발송이 실패하는데, 여기서 삼키고 링크를 로그에 남긴다 —
 * 토큰은 이미 DB 에 저장돼 있으니 개발 중에는 로그의 링크로 그대로 흐름을 확인할 수 있다.
 * publicdata 연동과 같은 원칙("한 건 실패로 전체를 죽이지 않는다")을 여기도 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetMailSender {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public void send(String toEmail, String resetUrl) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(toEmail);
            helper.setFrom(mailProperties.getFromAddress());
            helper.setSubject("[객석] 비밀번호 재설정 안내");
            helper.setText("""
                    비밀번호를 재설정하려면 아래 링크를 눌러주세요. 30분 안에만 유효합니다.

                    %s

                    본인이 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.
                    """.formatted(resetUrl));
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            log.warn("비밀번호 재설정 메일 발송 실패 — SMTP 설정을 확인할 것. to={}, resetUrl={}",
                    toEmail, resetUrl, e);
        }
    }
}
