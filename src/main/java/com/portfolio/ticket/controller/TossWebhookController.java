package com.portfolio.ticket.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.payment.TossPaymentClient;
import com.portfolio.ticket.payment.TossPaymentException;
import com.portfolio.ticket.payment.TossPaymentResult;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가상계좌 입금완료 등 비동기 결제 상태 변경을 받는 웹훅.
 * 웹훅 바디는 위조될 수 있으므로 그대로 믿지 않고, paymentKey 로 토스 결제조회 API를
 * 다시 호출해 실제 상태를 확인한 뒤에만 반영한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/toss")
@RequiredArgsConstructor
public class TossWebhookController {

    private final TossPaymentClient tossPaymentClient;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<Void> handle(@RequestBody JsonNode payload) {
        String paymentKey = payload.path("data").path("paymentKey").asText(null);
        if (paymentKey == null || paymentKey.isBlank()) {
            return ResponseEntity.ok().build();
        }

        TossPaymentResult result;
        try {
            result = tossPaymentClient.lookup(paymentKey);
        } catch (TossPaymentException e) {
            log.warn("웹훅 처리 중 결제 조회 실패. paymentKey={}, msg={}", paymentKey, e.getMessage());
            return ResponseEntity.ok().build();
        }

        reservationRepository.findByReservationNo(result.orderId()).ifPresent(reservation -> {
            if ("DONE".equals(result.status()) && reservation.getStatus() == ReservationStatus.PENDING) {
                reservationService.confirmPayment(reservation.getReservationNo(), result.paymentKey());
            }
        });

        return ResponseEntity.ok().build();
    }
}
