package com.portfolio.ticket.service;

import com.portfolio.ticket.repository.RestockSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 매진 회차 "빈자리 알림 받기" 구독 토글. {@link WishlistService} 와 같은 패턴이다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestockAlertService {

    private final RestockSubscriptionRepository restockSubscriptionRepository;
    private final RestockAlertWriter writer;

    /**
     * 구독 토글. 반환값은 호출 후의 상태다(true = 지금 구독 중). 따닥 클릭·경합 처리는
     * {@link WishlistService#toggle} 과 같다 — 검사-후-삽입 경쟁의 최종 판정은 유니크
     * 제약(uk_restock_subscription)에 맡기고, 위반은 "이미 구독함"으로 해석한다.
     *
     * <p>{@code @Transactional} 이 없는 이유도 같다 — 제약 위반을 삼키는 지점이 트랜잭션
     * 안이면 롤백 대상으로 표시된 채 커밋을 시도해 다시 터진다.
     */
    public boolean toggle(Long memberId, Long scheduleId) {
        if (writer.removeIfPresent(memberId, scheduleId)) {
            return false;
        }
        try {
            writer.add(memberId, scheduleId);
        } catch (DataIntegrityViolationException e) {
            log.debug("취소표 알림 구독 중복 요청. memberId={}, scheduleId={}", memberId, scheduleId);
        }
        return true;
    }

    /** 이 회차를 지금 구독 중인지. 공연 상세에서 버튼 상태(구독/해제)를 정할 때 쓴다. */
    @Transactional(readOnly = true)
    public boolean isSubscribed(Long memberId, Long scheduleId) {
        return memberId != null
                && restockSubscriptionRepository.existsByMemberIdAndScheduleId(memberId, scheduleId);
    }

    /**
     * 여러 회차 중 지금 구독 중인 것들. 공연 상세가 매진 회차마다 버튼 상태를 정할 때
     * 한 번에 묻는다(N+1 방지 — RestockSubscriptionRepository.findByMemberIdAndScheduleIdIn 참고).
     */
    @Transactional(readOnly = true)
    public Set<Long> subscribedScheduleIds(Long memberId, List<Long> scheduleIds) {
        if (memberId == null || scheduleIds.isEmpty()) {
            return Set.of();
        }
        return restockSubscriptionRepository.findByMemberIdAndScheduleIdIn(memberId, scheduleIds).stream()
                .map(s -> s.getSchedule().getId())
                .collect(Collectors.toSet());
    }
}
