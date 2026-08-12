package com.portfolio.ticket.domain;

public enum NotificationType {
    /** 찜한 공연에 예매 가능한 회차가 열렸다. */
    SCHEDULE_OPENED,
    /** 확정된 예매의 공연이 내일이다. */
    SCHEDULE_REMINDER,
    /** 확정된 예매의 관람일이 지났는데 아직 관람평을 안 썼다. */
    REVIEW_REQUESTED
}
