package com.portfolio.ticket.external;

/**
 * 수동 트리거(POST /api/admin/sync)와 새벽 4시 cron 이 겹치려 할 때 던진다.
 * 같은 PERFORMANCE_SCHEDULE 행을 두 트랜잭션이 동시에 쓰면 Concurrent update 로
 * 충돌하고 그 롤백마저 실패해 커넥션이 깨지는 사고를 실제로 겪었다(README 트러블슈팅).
 */
public class SyncAlreadyRunningException extends RuntimeException {

    public SyncAlreadyRunningException() {
        super("이미 다른 공공데이터 동기화가 진행 중입니다.");
    }
}
