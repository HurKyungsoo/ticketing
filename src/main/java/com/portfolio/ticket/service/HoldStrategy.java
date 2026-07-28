package com.portfolio.ticket.service;

/**
 * 좌석 선점 동시성 제어 전략. 5가지를 같은 조건에서 비교하기 위해 분리했다.
 *
 * 앞의 4개는 전부 단일 DB 안에서 끝나는 락이라 앱 서버를 늘려도 DB 가 병목이자
 * 유일한 조정자가 된다. DISTRIBUTED 는 그 조정자를 DB 밖(Redis)으로 빼는 쪽이다.
 */
public enum HoldStrategy {
    NONE,          // 락 없음 - 버그 재현용
    PESSIMISTIC,   // SELECT ... FOR UPDATE
    OPTIMISTIC,    // @Version + 재시도
    UNIQUE,        // seat_hold PK 충돌 이용
    DISTRIBUTED    // Redis SET NX PX + Lua CAS 해제
}
