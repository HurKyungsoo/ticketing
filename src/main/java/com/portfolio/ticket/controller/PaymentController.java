package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.payment.TossPaymentClient;
import com.portfolio.ticket.payment.TossPaymentException;
import com.portfolio.ticket.payment.TossPaymentResult;
import com.portfolio.ticket.payment.TossProperties;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.ForbiddenException;
import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/reservations/{reservationNo}/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final TossPaymentClient tossPaymentClient;
    private final TossProperties tossProperties;

    @GetMapping
    public String paymentPage(@PathVariable String reservationNo,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        Reservation reservation = getOwnedReservation(reservationNo, principal);

        // 남은 시간은 서버가 계산해 "초"로 넘긴다. 만료 시각(holdExpiresAt)을 그대로 주고
        // 브라우저가 자기 시계로 빼게 하면, 시계가 어긋난 기기에서 남은 시간이 실제와 달라진다
        // (몇 분 남았다고 표시하는데 서버는 이미 만료로 처리하는 상황).
        long remainingSeconds = Math.max(0,
                Duration.between(LocalDateTime.now(), reservation.getHoldExpiresAt()).toSeconds());
        // 결제할 수 없는 상태 — 이미 결제됐거나, 취소됐거나, 선점이 풀렸거나.
        boolean payable = reservation.getStatus() == ReservationStatus.PENDING && remainingSeconds > 0;

        model.addAttribute("reservation", reservation);
        model.addAttribute("schedule", reservation.getSchedule());
        model.addAttribute("performance", reservation.getSchedule().getPerformance());
        model.addAttribute("orderName", orderName(reservation));
        // 좌석 목록 표시용. ID 순 = 생성 순이라 화면마다 순서가 흔들리지 않는다.
        // 만료·취소된 예매는 좌석 연결이 끊겨 있으므로(화면은 seatSummary 스냅샷으로 대체) 빈 리스트다.
        model.addAttribute("seats", reservation.getSeats().stream()
                .sorted(Comparator.comparing(Seat::getId)).toList());
        model.addAttribute("remainingSeconds", remainingSeconds);
        model.addAttribute("payable", payable);
        model.addAttribute("tossClientKey", tossProperties.getClientKey());
        return "reservation/payment";
    }

    @GetMapping("/success")
    public String success(@PathVariable String reservationNo,
                           @RequestParam String paymentKey,
                           @RequestParam String orderId,
                           @RequestParam int amount,
                           @AuthenticationPrincipal CustomUserDetails principal,
                           Model model) {
        Reservation reservation = getOwnedReservation(reservationNo, principal);

        // 프론트가 보낸 orderId/amount 를 그대로 믿지 않고 서버가 알고 있는 예매 정보와 대조한다.
        if (!reservation.getReservationNo().equals(orderId) || reservation.getAmount() != amount) {
            throw new IllegalArgumentException("결제 금액 또는 주문번호가 예매 정보와 일치하지 않습니다.");
        }

        // 조기 차단: 토스 승인 API 를 호출하기 "전에" 선점이 이미 만료됐는지 확인한다.
        // 승인 후에 되돌리는 건 환불 절차라 비용이 훨씬 크므로, 알 수 있는 건 미리 막는다.
        if (reservation.getStatus() != ReservationStatus.PENDING
                || reservation.isHoldExpired(LocalDateTime.now())) {
            throw new IllegalStateException("좌석 선점이 만료되어 결제를 진행할 수 없습니다.");
        }

        TossPaymentResult result = tossPaymentClient.confirm(paymentKey, orderId, amount);

        try {
            reservationService.confirmPayment(reservationNo, result.paymentKey());
        } catch (IllegalStateException e) {
            // 조기 차단 이후에도, 토스 승인 API 가 응답하는 사이(네트워크 왕복 중)
            // HoldExpireScheduler 가 먼저 선점을 만료시킨 경우다. 이미 돈은 승인됐으므로
            // 토스 결제취소 API로 자동 환불하고 실패 화면 대신 환불 안내 화면을 보여준다.
            log.warn("결제 승인 후 확정 실패, 자동 환불 처리. reservationNo={}, paymentKey={}, msg={}",
                    reservationNo, result.paymentKey(), e.getMessage());
            tossPaymentClient.cancel(result.paymentKey(), "좌석 선점 만료로 인한 자동 환불", result.totalAmount());

            model.addAttribute("reservation", reservation);
            model.addAttribute("amount", result.totalAmount());
            // 이미 읽어 둔(detached) 좌석 스냅샷이다 — 이 시점엔 DB 에서는 release() 로
            // 연결이 끊긴 뒤지만, 앞서 join fetch 해 둔 메모리 상의 값은 그대로 남아 있어
            // "무엇을 환불받았는지" 보여주는 데 문제없다.
            model.addAttribute("seats", reservation.getSeats().stream()
                    .sorted(Comparator.comparing(Seat::getId)).toList());
            return "reservation/payment-refunded";
        }

        model.addAttribute("reservation", reservation);
        // 결제 화면과 같은 좌석별 등급·가격 표시를 완료 화면에도 쓴다. 결제 확정 직후라
        // 좌석 연결이 끊길 일이 없지만(release() 는 취소·만료 때만), 만약을 대비해 같은
        // 방식(빈 리스트면 화면이 seatSummary 로 대체)으로 안전하게 둔다.
        model.addAttribute("seats", reservation.getSeats().stream()
                .sorted(Comparator.comparing(Seat::getId)).toList());
        return "reservation/payment-success";
    }

    @GetMapping("/fail")
    public String fail(@PathVariable String reservationNo,
                        @RequestParam(required = false) String code,
                        @RequestParam(required = false) String message,
                        @AuthenticationPrincipal CustomUserDetails principal,
                        Model model) {
        model.addAttribute("code", code);
        model.addAttribute("message", message);
        addFailureContext(reservationNo, principal, model);
        return "reservation/payment-fail";
    }

    @ExceptionHandler(TossPaymentException.class)
    public String handleTossFailure(TossPaymentException e,
                                     @PathVariable String reservationNo,
                                     @AuthenticationPrincipal CustomUserDetails principal,
                                     Model model) {
        model.addAttribute("message", e.getMessage());
        addFailureContext(reservationNo, principal, model);
        return "reservation/payment-fail";
    }

    /**
     * 실패 화면에도 결제 화면과 같은 주문 정보(포스터·좌석)를 보여준다 — 예매번호 하나만
     * 뜨면 뭘 사려던 건지 결제 화면으로 되돌아가야 알 수 있다. 실패 직후에는 좌석 선점이
     * 아직 살아 있는 게 보통이라(사용자가 결제창을 취소했거나 카드가 거절된 것뿐, 선점
     * 자체는 안 풀렸다) seats 가 비어 있을 일이 드물지만, 혹시 그 사이 만료됐더라도
     * 결제 화면과 같은 방식(빈 리스트 → seatSummary 폴백)으로 화면이 안전하게 대체한다.
     */
    private void addFailureContext(String reservationNo, CustomUserDetails principal, Model model) {
        Reservation reservation = getOwnedReservation(reservationNo, principal);
        model.addAttribute("reservation", reservation);
        model.addAttribute("seats", reservation.getSeats().stream()
                .sorted(Comparator.comparing(Seat::getId)).toList());
    }

    private Reservation getOwnedReservation(String reservationNo, CustomUserDetails principal) {
        Reservation reservation = reservationRepository.findWithSeatDetailsByReservationNo(reservationNo)
                .orElseThrow(() -> new NotFoundException("예매 내역을 찾을 수 없습니다."));
        if (!reservation.getMemberId().equals(principal.getMemberId())) {
            // 남의 예매다. 종전에는 IllegalStateException 이라 Whitelabel 500 이 나갔다.
            // 예매번호를 메시지에 담지 않는다 — 존재 여부를 알려줄 이유가 없다.
            throw new ForbiddenException("본인 예매만 결제할 수 있습니다.");
        }
        return reservation;
    }

    /**
     * 토스에 넘길 주문명.
     *
     * <p>공연명은 좌석이 아니라 예매가 직접 든 회차에서 가져오고, 좌석 수도 살아 있는
     * 좌석이 아니라 없으면 스냅샷으로 대체한다 — 선점이 만료되면 좌석이 0개가 되는데
     * {@code seats.get(0)} 을 쓰면 그 순간 결제 페이지가 통째로 터진다(만료 안내를
     * 띄워야 할 자리에서 500). 만료된 예매는 결제 자체가 막히므로 이 값은 표시용이다.
     */
    private String orderName(Reservation reservation) {
        String title = reservation.getSchedule().getPerformance().getTitle();
        List<Seat> seats = reservation.getSeats();
        if (seats.isEmpty()) {
            return title;
        }
        String first = seats.get(0).seatLabel();
        return seats.size() == 1
                ? title + " - " + first
                : title + " - " + first + " 외 " + (seats.size() - 1) + "석";
    }
}
