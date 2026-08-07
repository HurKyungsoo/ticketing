package com.portfolio.ticket.service;

/**
 * 예매 가능한 회차가 하나도 없던 공연에 회차가 열렸다.
 *
 * <p>동기화(PerformanceSyncService.sync)가 도는 <b>트랜잭션 안에서</b> 발행되지만,
 * 받는 쪽은 커밋된 뒤에만 동작한다({@code @TransactionalEventListener}). 이렇게 나눈 이유:
 *
 * <ul>
 *   <li>동기화는 수백 건을 도는 배치다. 그 안에서 알림까지 만들면 배치가 그만큼 길어지고,
 *       알림 생성이 실패하면 수집 전체가 롤백된다 — 알림 때문에 공연 데이터를 잃는 건
 *       득실이 안 맞는다.
 *   <li>롤백된 동기화에 대해 알림이 나가면 안 된다. "회차가 열렸다"고 알렸는데 실제로는
 *       커밋이 안 된 상태가 된다.
 * </ul>
 */
public record ScheduleOpenedEvent(Long performanceId) {}
