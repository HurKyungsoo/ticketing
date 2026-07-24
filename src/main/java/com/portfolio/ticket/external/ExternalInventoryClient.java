package com.portfolio.ticket.external;

/**
 * 실제 박스오피스/티켓 판매 파트너와의 실시간 재고 연동 포트.
 *
 * 현재는 접근 가능한 실제 파트너 API 가 없어 {@link MockExternalInventoryClient} 만
 * 존재한다. 실제 파트너가 생기면 이 인터페이스의 구현체만 교체하면 되고,
 * {@code ReservationService} 등 호출부는 바뀌지 않는다.
 */
public interface ExternalInventoryClient {

    /**
     * 외부 시스템 기준 잔여석을 조회한다.
     * 파트너가 없거나 조회에 실패하면 null 을 반환해 로컬 재고(PerformanceSchedule)를
     * 그대로 신뢰하도록 한다 (외부 연동 실패가 로컬 예매를 막아서는 안 된다).
     */
    Integer fetchRemainingSeats(Long scheduleId);

    /** 좌석 판매 확정을 외부 시스템에 알린다. 실패해도 우리 쪽 예매는 이미 확정된 상태를 유지한다. */
    void notifySold(Long scheduleId, int amount);

    /** 좌석 취소/해제를 외부 시스템에 알린다. 실패해도 우리 쪽 취소는 이미 반영된 상태를 유지한다. */
    void notifyReleased(Long scheduleId, int amount);
}
