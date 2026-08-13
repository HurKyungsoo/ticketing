package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.domain.RestockSubscription;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.RestockSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 취소표 알림 구독 쓰기의 트랜잭션 경계. {@link WishlistWriter} 와 같은 이유로
 * {@link RestockAlertService} 와 분리한다 — <b>유니크 제약 위반을 삼키는 쪽은 트랜잭션
 * 밖이어야 한다</b>(WishlistWriter 주석 참고).
 */
@Service
@RequiredArgsConstructor
public class RestockAlertWriter {

    private final RestockSubscriptionRepository restockSubscriptionRepository;
    private final PerformanceScheduleRepository scheduleRepository;

    /** 지운 게 있으면 true. 토글의 해제 쪽이다. */
    @Transactional
    public boolean removeIfPresent(Long memberId, Long scheduleId) {
        return restockSubscriptionRepository.deleteByMemberIdAndScheduleId(memberId, scheduleId) > 0;
    }

    /**
     * 구독 추가. 이미 있으면 {@code DataIntegrityViolationException} 이 그대로 올라간다
     * ({@code saveAndFlush} 인 이유는 WishlistWriter.add 와 같다).
     */
    @Transactional
    public void add(Long memberId, Long scheduleId) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new NotFoundException("회차를 찾을 수 없습니다."));

        restockSubscriptionRepository.saveAndFlush(RestockSubscription.builder()
                .memberId(memberId)
                .schedule(schedule)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
