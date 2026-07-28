package com.portfolio.ticket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 기반 좌석 분산 락. (HoldStrategy.DISTRIBUTED)
 *
 * 획득: SET seat:lock:{seatId} {token} NX PX {ttl}
 *   - NX 라서 키가 없을 때만 성공한다. "검사 후 설정"이 한 명령으로 원자 처리되므로
 *     GET → 없으면 SET 같은 2단계 구현에서 생기는 경쟁이 없다.
 *   - PX(TTL) 가 있어서 락을 쥔 서버가 그대로 죽어도 락이 영구히 남지 않는다.
 *     DB 락과 달리 커넥션이 끊겨도 자동으로 풀리지 않기 때문에 TTL 이 유일한 안전장치다.
 *
 * 해제: Lua 스크립트로 "내 토큰일 때만 삭제"(compare-and-delete)
 *   - 단순 DEL 로 지우면, 내 작업이 TTL 을 넘겨 락이 이미 만료되고 다른 요청이
 *     같은 키를 새로 잡은 상태에서 내가 남의 락을 지워버릴 수 있다.
 *   - GET 으로 확인하고 DEL 하는 2단계로는 그 사이에 만료가 끼어들 수 있어서,
 *     Redis 가 단일 스레드로 원자 실행하는 Lua 안에서 비교와 삭제를 함께 한다.
 *
 * 한계(README 참고): 단일 Redis 인스턴스 락이라 페일오버 중에는 두 클라이언트가
 * 같은 락을 쥘 수 있다. 그래서 이 프로젝트에서는 최종 방어선을 DB 쪽에 그대로 남겨둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSeatLock {

    private static final String KEY_PREFIX = "seat:lock:";

    /** 내 토큰일 때만 지운다. 반환값 1 = 내가 지움, 0 = 이미 만료됐거나 남의 락. */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(Long seatId, String token, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key(seatId), token, ttl);   // SET ... NX PX
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(Long seatId, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key(seatId)), token);
        } catch (Exception e) {
            // 해제 실패해도 TTL 이 지나면 자동으로 풀리므로 요청 자체를 실패시키지 않는다.
            log.warn("좌석 락 해제 실패. seatId={}, msg={}", seatId, e.getMessage());
        }
    }

    /**
     * 여러 좌석을 seatId 오름차순으로 잠근다. 순서를 고정해야 서로 겹치는 두 요청이
     * 반대 순서로 잠그다 서로를 기다리는 상황을 막을 수 있다 (DB 락과 같은 규칙).
     * 하나라도 실패하면 그때까지 잡은 락을 전부 풀고 실패한 좌석 목록을 알려준다.
     */
    public List<Long> tryLockAll(List<Long> sortedSeatIds, String token, Duration ttl) {
        List<Long> acquired = new ArrayList<>();
        for (Long seatId : sortedSeatIds) {
            if (tryLock(seatId, token, ttl)) {
                acquired.add(seatId);
                continue;
            }
            unlockAll(acquired, token);
            return List.of(seatId);   // 실패한 좌석
        }
        return List.of();             // 전부 획득
    }

    public void unlockAll(List<Long> seatIds, String token) {
        for (Long seatId : seatIds) {
            unlock(seatId, token);
        }
    }

    private String key(Long seatId) {
        return KEY_PREFIX + seatId;
    }
}
