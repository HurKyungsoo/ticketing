package com.portfolio.ticket.service;

/** 한 회원이 짧은 시간 안에 좌석 선점을 너무 많이 시도했을 때 (매크로/봇 대응). */
public class TooManyHoldAttemptsException extends RuntimeException {
    public TooManyHoldAttemptsException(String message) {
        super(message);
    }
}
