package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.payment.TossPaymentClient;
import com.portfolio.ticket.payment.TossPaymentException;
import com.portfolio.ticket.payment.TossPaymentResult;
import com.portfolio.ticket.payment.TossProperties;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

        model.addAttribute("reservation", reservation);
        model.addAttribute("orderName", orderName(reservation));
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

        TossPaymentResult result = tossPaymentClient.confirm(paymentKey, orderId, amount);
        reservationService.confirmPayment(reservationNo, result.paymentKey());

        model.addAttribute("reservation", reservation);
        return "reservation/payment-success";
    }

    @GetMapping("/fail")
    public String fail(@RequestParam(required = false) String code,
                        @RequestParam(required = false) String message,
                        Model model) {
        model.addAttribute("code", code);
        model.addAttribute("message", message);
        return "reservation/payment-fail";
    }

    @ExceptionHandler(TossPaymentException.class)
    public String handleTossFailure(TossPaymentException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "reservation/payment-fail";
    }

    private Reservation getOwnedReservation(String reservationNo, CustomUserDetails principal) {
        Reservation reservation = reservationRepository.findWithSeatDetailsByReservationNo(reservationNo)
                .orElseThrow(() -> new IllegalArgumentException("예매 내역 없음. no=" + reservationNo));
        if (!reservation.getMemberId().equals(principal.getMemberId())) {
            throw new IllegalStateException("본인 예매만 결제할 수 있습니다.");
        }
        return reservation;
    }

    private String orderName(Reservation reservation) {
        Seat seat = reservation.getSeat();
        return seat.getSchedule().getPerformance().getTitle() + " - " + seat.seatLabel();
    }
}
