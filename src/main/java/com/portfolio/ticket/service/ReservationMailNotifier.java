package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.mail.MailProperties;
import com.portfolio.ticket.mail.ReservationMailSender;
import com.portfolio.ticket.repository.MemberRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

/**
 * 예매가 확정되면 확인 메일을 보낸다.
 *
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW} 는 {@link NotificationService} 의 두 리스너와
 * 같은 형태다. 이유는 {@link ReservationConfirmedEvent} 주석에 적었다 — 요약하면 좌석 락을
 * 쥔 트랜잭션 안에서 SMTP 를 기다리면 안 되고, 메일 실패가 확정을 롤백시켜도 안 된다.
 *
 * <p>값을 트랜잭션 안에서 전부 꺼내 {@link ReservationMailSender.Content} 로 넘긴다. 엔티티를
 * 그대로 넘기면 발송 시점에 지연 로딩이 걸리는데, 그때는 이미 트랜잭션이 끝나 있을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationMailNotifier {

    private final ReservationRepository reservationRepository;
    private final MemberRepository memberRepository;
    private final ReservationMailSender mailSender;
    private final MailProperties mailProperties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationConfirmed(ReservationConfirmedEvent event) {
        try {
            sendConfirmed(event.reservationId());
        } catch (Exception e) {
            // 확정은 이미 커밋됐다. 메일 하나 때문에 결제 로그가 오염되면 안 된다
            // (NotificationService 의 리스너와 같은 원칙).
            log.warn("예매 확인 메일 준비 실패(무시하고 계속). reservationId={}, msg={}",
                    event.reservationId(), e.getMessage());
        }
    }

    private void sendConfirmed(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return;
        }

        Member member = memberRepository.findById(reservation.getMemberId()).orElse(null);
        if (member == null || member.getEmail() == null || member.getEmail().isBlank()) {
            // 이메일 제공에 동의하지 않은 소셜 계정은 email 이 계속 null 이다(Member.email 주석).
            // 보낼 곳이 없을 뿐 예매는 정상이므로 조용히 끝낸다 — 확인 메일을 확실히 받게 하려면
            // 가입 시 이메일을 검증하는 별도 작업이 필요하다.
            log.info("예매 확인 메일 건너뜀 - 받을 이메일이 없는 계정. reservationId={}", reservationId);
            return;
        }

        PerformanceSchedule schedule = reservation.getSchedule();
        mailSender.sendConfirmed(new ReservationMailSender.Content(
                member.getEmail(),
                member.getNickname(),
                reservation.getReservationNo(),
                schedule.getPerformance().getTitle(),
                schedule.getPerformance().getVenue(),
                schedule.getShowAt(),
                reservation.getSeatSummary(),
                reservation.getAmount(),
                mailProperties.getBaseUrl() + "/mypage/reservations"));
    }
}
