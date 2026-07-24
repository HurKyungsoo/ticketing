package com.portfolio.ticket.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 실제 파트너가 없을 때 쓰는 개발/포트폴리오용 구현체.
 * 외부 재고 제약이 없다고 가정하고 로컬 재고를 그대로 신뢰하며, 알림은 로그만 남긴다.
 */
@Slf4j
@Component
public class MockExternalInventoryClient implements ExternalInventoryClient {

    @Override
    public Integer fetchRemainingSeats(Long scheduleId) {
        log.debug("[MOCK] 외부 재고 조회 - 연동된 파트너가 없어 로컬 재고를 그대로 신뢰합니다. scheduleId={}", scheduleId);
        return null;
    }

    @Override
    public void notifySold(Long scheduleId, int amount) {
        log.info("[MOCK] 외부 시스템에 판매 알림. scheduleId={}, amount={}", scheduleId, amount);
    }

    @Override
    public void notifyReleased(Long scheduleId, int amount) {
        log.info("[MOCK] 외부 시스템에 취소/해제 알림. scheduleId={}, amount={}", scheduleId, amount);
    }
}
