package com.portfolio.ticket.domain;

public enum SeatStatus {
    AVAILABLE,  // 예매 가능
    HELD,       // 선점(결제 대기) - 만료되면 배치가 AVAILABLE 로 되돌림
    SOLD        // 결제 완료
}
