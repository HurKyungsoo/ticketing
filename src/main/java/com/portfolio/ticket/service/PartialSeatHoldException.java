package com.portfolio.ticket.service;

import java.util.List;

/**
 * 다중 좌석 선점 중 일부 좌석이 이미 선점/판매돼 있을 때.
 * 트랜잭션을 롤백시켜(전부 실패 처리) 부분 성공 상태가 남지 않게 하고,
 * 실패한 seatId 목록을 컨트롤러가 그대로 클라이언트에 돌려줄 수 있게 들고 있다.
 */
public class PartialSeatHoldException extends RuntimeException {

    private final List<Long> failedSeatIds;

    public PartialSeatHoldException(String message, List<Long> failedSeatIds) {
        super(message);
        this.failedSeatIds = failedSeatIds;
    }

    public List<Long> getFailedSeatIds() {
        return failedSeatIds;
    }
}
