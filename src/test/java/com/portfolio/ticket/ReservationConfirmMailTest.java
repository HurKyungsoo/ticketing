package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.ReservationService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 예매 확정 안내 메일.
 *
 * <p>이 기능의 불변식은 "메일은 예매에 영향을 주지 않는다" 하나다. 확인 메일은 결제 승인이
 * 끝난 뒤의 부가 작업이라, 메일 쪽에서 무슨 일이 나도 이미 승인된 결제가 예매 없이 남는
 * 상황을 만들면 안 된다. 그래서 발송은 커밋 후 이벤트에서 하고, 실패는 삼킨다
 * ({@code ReservationConfirmedEvent} 주석).
 *
 * <p>SMTP 는 {@link JavaMailSender} 를 목으로 갈아끼워 가로챈다 — 발송 직전까지의 경로
 * (이벤트 발행 → 리스너 → 본문 생성)는 전부 실제 코드가 돈다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationConfirmMailTest {

    @MockBean JavaMailSender javaMailSender;

    @Autowired ReservationService reservationService;
    @Autowired MemberRepository memberRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;

    private Long seatId;
    private Long memberWithEmailId;
    private Long memberWithoutEmailId;

    @BeforeEach
    void setUp() {
        // 삭제 순서는 FK 방향을 따른다(ReservationConcurrencyTest.setUp 주석과 같은 이유).
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        // 목이 그냥 null 을 주면 MimeMessageHelper 가 NPE 를 낸다. 실제 MimeMessage 를 쥐여줘야
        // 본문 생성까지 진짜 코드가 돈다.
        when(javaMailSender.createMimeMessage())
                .thenAnswer(invocation -> new JavaMailSenderImpl().createMimeMessage());

        memberWithEmailId = memberRepository.save(Member.builder()
                .loginId("mailtarget")
                .password("encoded")
                .nickname("메일받는사람")
                .email("target@example.com")
                .createdAt(LocalDateTime.now())
                .build()).getId();

        // 이메일 제공에 동의하지 않은 소셜 계정을 흉내낸다(Member.email 은 nullable).
        memberWithoutEmailId = memberRepository.save(Member.builder()
                .loginId("noemail")
                .password("encoded")
                .nickname("이메일없음")
                .authProvider(AuthProvider.KAKAO)
                .providerId("kakao-1")
                .createdAt(LocalDateTime.now())
                .build()).getId();

        Performance performance = performanceRepository.save(Performance.builder()
                .externalId("MAIL-" + System.nanoTime())
                .title("확인메일 테스트 공연")
                .venue("테스트홀")
                .startDate(LocalDate.now().plusDays(30))
                .endDate(LocalDate.now().plusDays(31))
                .totalSeatCount(10)
                .basePrice(50_000)
                .build());

        PerformanceSchedule schedule = scheduleRepository.save(PerformanceSchedule.builder()
                .performance(performance)
                .showAt(LocalDateTime.now().plusDays(30).withHour(19).withMinute(30).withSecond(0).withNano(0))
                .totalSeats(10)
                .remainingSeats(10)
                .build());

        seatId = seatRepository.save(Seat.builder()
                .schedule(schedule)
                .section("1층")
                .rowNo(3)
                .seatNo(12)
                .grade(SeatGrade.VIP)
                .status(SeatStatus.AVAILABLE)
                .price(75_000)
                .build()).getId();
    }

    /** 선점 → 확정. 확정이 커밋되는 시점에 AFTER_COMMIT 리스너가 같은 스레드에서 돈다. */
    private String confirm(Long memberId) {
        Reservation reservation = reservationService.holdWithPessimisticLock(seatId, memberId);
        reservationService.confirmPayment(reservation.getReservationNo(), "test-payment-key");
        return reservation.getReservationNo();
    }

    private String sentBody() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue().getContent().toString();
    }

    @DisplayName("예매가 확정되면 예매번호·공연·좌석·금액이 담긴 확인 메일이 나간다")
    @Test
    void sendsConfirmationMail() throws Exception {
        String reservationNo = confirm(memberWithEmailId);

        String body = sentBody();
        assertThat(body)
                .contains(reservationNo)
                .contains("확인메일 테스트 공연")
                .contains("테스트홀")
                .contains("1층 3열 12번")
                .contains("75,000원")
                .contains("/mypage/reservations");
    }

    @DisplayName("받을 이메일이 없는 계정이면 발송을 건너뛴다 — 예매는 그대로 확정된다")
    @Test
    void skipsWhenMemberHasNoEmail() {
        String reservationNo = confirm(memberWithoutEmailId);

        verify(javaMailSender, never()).send(any(MimeMessage.class));
        assertThat(reservationRepository.findByReservationNo(reservationNo))
                .get()
                .extracting(Reservation::getStatus)
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    /**
     * 이 기능의 핵심 불변식. 메일 발송이 확정 트랜잭션 안으로 들어가면 이 테스트가 깨진다 —
     * 그때는 메일 실패가 이미 승인된 결제를 롤백시켜 "돈만 나간" 상태를 만든다.
     *
     * <p>{@code MailException} 이 아닌 예외를 쓴 이유는 {@code ReservationMailSender} 가
     * 삼키는 범위(MailException/MessagingException) 밖에서 터뜨려, 리스너 쪽 방어까지
     * 함께 확인하기 위해서다.
     */
    @DisplayName("메일 발송이 터져도 예매는 확정된 채로 남는다")
    @Test
    void mailFailureDoesNotRollbackConfirmation() {
        when(javaMailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP 폭발"));

        String reservationNo = confirm(memberWithEmailId);

        assertThat(reservationRepository.findByReservationNo(reservationNo))
                .get()
                .extracting(Reservation::getStatus)
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seatRepository.findById(seatId))
                .get()
                .extracting(Seat::getStatus)
                .isEqualTo(SeatStatus.SOLD);
    }

    /**
     * 규정 요율을 본문에 문자열로 박아두면 {@code REFUND_TIERS} 를 고칠 때 메일만 옛날 값으로
     * 남는다 — 고지한 요율과 실제 청구가 달라지는 건 표기 오류가 아니다
     * ({@code Reservation.RefundTier} 주석). 여기서는 목록에서 만들어졌는지를 확인한다.
     */
    @DisplayName("취소·환불 규정 문구가 REFUND_TIERS 와 어긋나지 않는다")
    @Test
    void refundPolicyIsDerivedFromTiers() throws Exception {
        confirm(memberWithEmailId);

        String body = sentBody();
        for (Reservation.RefundTier tier : Reservation.REFUND_TIERS) {
            assertThat(body).contains(tier.feeRate() == 0
                    ? "관람일 %d일 전까지: 수수료 없음".formatted(tier.minDaysBefore())
                    : "관람일 %d일 전까지: 결제금액의 %d%%".formatted(tier.minDaysBefore(), tier.feeRate()));
        }
        assertThat(body).contains("관람일 당일: 취소 불가");
    }
}
