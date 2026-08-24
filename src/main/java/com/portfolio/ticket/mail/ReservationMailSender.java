package com.portfolio.ticket.mail;

import com.portfolio.ticket.domain.Reservation;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 예매 확정 안내 메일. {@link PasswordResetMailSender} 와 같은 이유로 발송 실패를 여기서
 * 삼킨다 — 메일이 안 나가도 예매는 이미 확정됐고, 확정을 되돌릴 수는 없다.
 *
 * <p><b>취소·환불 규정은 {@link Reservation#REFUND_TIERS} 를 읽어서 만든다.</b> 요율을 메일
 * 본문에 문자열로 다시 적으면 규정을 고칠 때 이쪽만 옛날 값으로 남는데, 고지한 요율과 실제로
 * 청구되는 금액이 다른 건 표기 오류가 아니라 분쟁거리다. 공연 상세 화면이 같은 목록을 읽어
 * 그리는 것과 같은 이유다({@code Reservation.RefundTier} 주석).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationMailSender {

    private static final DateTimeFormatter SHOW_AT =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일(E) a h:mm", Locale.KOREAN);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    /**
     * 메일에 담을 값. 엔티티를 그대로 받지 않는 이유는 이 시점이 트랜잭션 밖이라
     * 지연 로딩이 터질 수 있기 때문이다 — 부르는 쪽이 트랜잭션 안에서 다 꺼내 넘긴다.
     */
    public record Content(
            String toEmail,
            String nickname,
            String reservationNo,
            String performanceTitle,
            String venue,
            LocalDateTime showAt,
            String seatSummary,
            int amount,
            String myPageUrl
    ) {}

    public void sendConfirmed(Content c) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(c.toEmail());
            helper.setFrom(mailProperties.getFromAddress());
            helper.setSubject("[객석] 예매가 확정되었습니다 - " + c.performanceTitle());
            helper.setText(body(c));
            mailSender.send(message);
        } catch (MailException | MessagingException e) {
            log.warn("예매 확인 메일 발송 실패 — SMTP 설정을 확인할 것. to={}, reservationNo={}",
                    c.toEmail(), c.reservationNo(), e);
        }
    }

    private String body(Content c) {
        return """
                %s님, 예매가 확정되었습니다.

                  예매번호   %s
                  공연       %s
                  일시       %s
                  장소       %s
                  좌석       %s
                  결제금액   %s원

                예매 내역 확인·취소
                %s

                관람 당일 매표소에서 예매번호를 말씀하시면 티켓을 받으실 수 있습니다.

                [취소·환불 규정]
                %s
                """.formatted(
                c.nickname(),
                c.reservationNo(),
                c.performanceTitle(),
                SHOW_AT.format(c.showAt()),
                c.venue() == null ? "-" : c.venue(),
                c.seatSummary(),
                String.format("%,d", c.amount()),
                c.myPageUrl(),
                refundPolicy());
    }

    /**
     * 규정 문구를 {@link Reservation#REFUND_TIERS} 에서 만든다. 마지막 줄(당일 취소 불가)은
     * 목록에 없는 fall-through 라 따로 적는다 — {@code refundFeeRate} 가 어느 단계에도
     * 걸리지 않으면 예외를 던지는 그 경우다.
     */
    private String refundPolicy() {
        StringBuilder sb = new StringBuilder();
        for (Reservation.RefundTier tier : Reservation.REFUND_TIERS) {
            sb.append(tier.feeRate() == 0
                    ? "- 관람일 %d일 전까지: 수수료 없음%n".formatted(tier.minDaysBefore())
                    : "- 관람일 %d일 전까지: 결제금액의 %d%%%n".formatted(tier.minDaysBefore(), tier.feeRate()));
        }
        sb.append("- 관람일 당일: 취소 불가");
        return sb.toString();
    }
}
