package com.portfolio.ticket.service;

/**
 * 좌석 선점 동시성 제어 전략. 4가지를 같은 조건에서 비교하기 위해 분리했다.
 */
public enum HoldStrategy {
    NONE,          // 락 없음 - 버그 재현용
    PESSIMISTIC,   // SELECT ... FOR UPDATE
    OPTIMISTIC,    // @Version + 재시도
    UNIQUE         // seat_hold PK 충돌 이용
}
