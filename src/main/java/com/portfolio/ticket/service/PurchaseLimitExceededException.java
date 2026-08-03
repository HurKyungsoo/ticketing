package com.portfolio.ticket.service;

/** 한 회원이 같은 회차에서 보유 가능한 좌석 수(매수 제한)를 넘겼을 때. */
public class PurchaseLimitExceededException extends RuntimeException {
    public PurchaseLimitExceededException(String message) {
        super(message);
    }
}
