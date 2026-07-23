package com.portfolio.ticket.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 토스페이먼츠 결제 승인/취소/조회 API 클라이언트. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentClient {

    private final RestClient tossRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 결제 승인. amount 는 호출 전에 서버가 reservation.amount 와 대조 검증했다고 가정한다. */
    public TossPaymentResult confirm(String paymentKey, String orderId, int amount) {
        try {
            JsonNode body = tossRestClient.post()
                    .uri("/v1/payments/confirm")
                    .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                    .retrieve()
                    .body(JsonNode.class);
            return toResult(body);
        } catch (HttpStatusCodeException e) {
            log.warn("토스 결제 승인 실패. orderId={}, msg={}", orderId, extractMessage(e));
            throw new TossPaymentException(extractMessage(e));
        }
    }

    /** 결제 취소(환불). cancelAmount 를 생략하면 전액 취소. */
    public void cancel(String paymentKey, String cancelReason, Integer cancelAmount) {
        try {
            Map<String, Object> body = cancelAmount == null
                    ? Map.of("cancelReason", cancelReason)
                    : Map.of("cancelReason", cancelReason, "cancelAmount", cancelAmount);

            tossRestClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            log.warn("토스 결제 취소 실패. paymentKey={}, msg={}", paymentKey, extractMessage(e));
            throw new TossPaymentException(extractMessage(e));
        }
    }

    /** 웹훅 검증용. 웹훅 바디를 그대로 믿지 않고 이 API로 실제 상태를 재조회한다. */
    public TossPaymentResult lookup(String paymentKey) {
        try {
            JsonNode body = tossRestClient.get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .retrieve()
                    .body(JsonNode.class);
            return toResult(body);
        } catch (HttpStatusCodeException e) {
            log.warn("토스 결제 조회 실패. paymentKey={}, msg={}", paymentKey, extractMessage(e));
            throw new TossPaymentException(extractMessage(e));
        }
    }

    private TossPaymentResult toResult(JsonNode body) {
        return new TossPaymentResult(
                body.path("paymentKey").asText(),
                body.path("orderId").asText(),
                body.path("status").asText(),
                body.path("totalAmount").asInt());
    }

    private String extractMessage(HttpStatusCodeException e) {
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString());
            return error.path("message").asText(e.getMessage());
        } catch (Exception parseError) {
            return e.getMessage();
        }
    }
}
