package com.portfolio.ticket.service;

/**
 * 공연이 처음으로 수집됐다(그전엔 우리 DB에 없던 공연).
 *
 * <p>{@link ScheduleOpenedEvent} 와 같은 이유로 발행 지점(동기화 트랜잭션 안)과 처리 지점
 * (커밋 후)을 나눈다 — 저장한 검색 조건과의 매칭·알림 생성이 실패해도 수집 자체가
 * 롤백되면 안 되고, 롤백된 수집에 대해 알림이 나가서도 안 된다.
 *
 * <p>{@code ScheduleOpenedEvent} 는 "이미 있던 공연에 회차가 새로 생겼다"는 뜻이라 다르다 —
 * 저장한 검색은 "이 조건에 맞는 공연이 세상에 새로 나타났다"에 반응해야 하므로, 기존
 * 공연이 회차만 보충되는 경우(topUpSchedules)까지 매칭 대상으로 삼지 않는다. 매번 다시
 * 알리면 등록 후 몇 달간 회차가 보충될 때마다 같은 공연으로 알림이 반복될 수 있다.
 */
public record PerformanceRegisteredEvent(Long performanceId) {}
