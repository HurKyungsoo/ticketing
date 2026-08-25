package com.portfolio.ticket.domain;

public enum NotificationType {
    /** 찜한 공연에 예매 가능한 회차가 열렸다. */
    SCHEDULE_OPENED,
    /** 구독한(매진) 회차에서 좌석이 하나 이상 풀렸다. */
    SEAT_AVAILABLE,
    /** 확정된 예매의 공연이 내일이다. */
    SCHEDULE_REMINDER,
    /** 확정된 예매의 관람일이 지났는데 아직 관람평을 안 썼다. */
    REVIEW_REQUESTED,
    /** 저장해 둔 검색 조건에 맞는 공연이 새로 등록됐다. */
    SAVED_SEARCH_MATCH
}
