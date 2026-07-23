package com.portfolio.ticket.domain;

public enum ReservationStatus {
    PENDING,    // 좌석 선점, 결제 대기
    CONFIRMED,  // 결제 완료
    CANCELED,   // 사용자 취소 / 환불
    EXPIRED     // 결제 시간 초과
}
