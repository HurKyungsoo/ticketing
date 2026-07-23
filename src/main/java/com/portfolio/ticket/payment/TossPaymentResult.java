package com.portfolio.ticket.payment;

public record TossPaymentResult(String paymentKey, String orderId, String status, int totalAmount) {
}
