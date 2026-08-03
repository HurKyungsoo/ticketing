package com.portfolio.ticket.service;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 좌석 선점(hold) 시도 rate limit. 성공/실패와 무관하게 "시도" 자체를 센다 — 매크로/봇이
 * 짧은 시간에 hold API 를 연타하는 걸 막는 게 목적이라 결과는 상관없다.
 *
 * Redis 는 {@link HoldStrategy#DISTRIBUTED} 전용 선택 인프라라 필수 의존성으로 만들지 않는다
 * (CLAUDE.md/README 원칙). 그래서 인메모리 슬라이딩 윈도로 구현했고, 단일 인스턴스 배포
 * 전제와 맞는다 — 인스턴스를 여러 대로 늘리면 인스턴스별로 따로 세게 되므로 이때는 Redis 기반으로
 * 바꿔야 한다.
 */
@Component
public class HoldRateLimiter {

    private static final int MAX_ATTEMPTS = 8;
    private static final long WINDOW_MS = 10_000L;

    private final ConcurrentHashMap<Long, Deque<Long>> attempts = new ConcurrentHashMap<>();

    /** 한도를 넘었으면 예외를 던지고, 아니면 이번 시도를 기록한다. */
    public void checkAndRecord(Long memberId) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = attempts.computeIfAbsent(memberId, id -> new ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            long windowStart = now - WINDOW_MS;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_ATTEMPTS) {
                throw new TooManyHoldAttemptsException("선점 시도가 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
            }
            timestamps.addLast(now);
        }
    }
}
