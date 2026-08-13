package com.portfolio.ticket.domain;

public enum NotificationType {
    /** 찜한 공연에 예매 가능한 회차가 열렸다. */
    SCHEDULE_OPENED,
    /** 구독한(매진) 회차에서 좌석이 하나 이상 풀렸다. */
    SEAT_AVAILABLE
}
