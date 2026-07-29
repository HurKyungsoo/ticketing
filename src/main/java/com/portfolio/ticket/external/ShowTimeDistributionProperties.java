package com.portfolio.ticket.external;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 회차 시각 분포(showtime-distribution.yml). PerformanceSyncService 가 참조한다.
 *
 * 원본에 공연 시간 정보가 없을 때 쓰는 대체 규칙이다. 예전에는 19:00 고정이었는데,
 * KOPIS 시간대별 상연 통계의 실측 분포를 따르도록 바꿨다. 근거는 yml 주석 참고.
 *
 * 설정이 비어 있으면 {@link #pick} 이 null 을 돌려주고 호출부가 기존 고정 시각으로
 * 대체한다 — 설정 파일이 없어도 앱은 그대로 동작해야 한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "showtime")
public class ShowTimeDistributionProperties {

    private List<Slot> slots = new ArrayList<>();

    @Getter
    @Setter
    public static class Slot {
        private LocalTime time;
        private int weight;
    }

    /**
     * 가중치에 비례해 시각 하나를 고른다.
     *
     * 난수를 쓰되 seed 를 받아 <b>같은 입력이면 항상 같은 결과</b>가 나오게 한다.
     * 회차 생성은 신규 공연일 때만 도는데, 그래도 결과가 매번 달라지면 테스트에서
     * 재현이 안 되고 배치를 다시 돌렸을 때 설명하기 어려운 차이가 생긴다.
     *
     * @return 고른 시각. 설정이 비었거나 가중치 합이 0 이면 null.
     */
    public LocalTime pick(long seed) {
        int total = 0;
        for (Slot slot : slots) {
            if (slot.getTime() != null && slot.getWeight() > 0) {
                total += slot.getWeight();
            }
        }
        if (total == 0) return null;

        int target = new Random(seed).nextInt(total);
        int cumulative = 0;
        for (Slot slot : slots) {
            if (slot.getTime() == null || slot.getWeight() <= 0) continue;

            cumulative += slot.getWeight();
            if (target < cumulative) return slot.getTime();
        }
        // 누적 계산상 도달할 수 없지만, 방어적으로 마지막 유효 슬롯을 돌려준다.
        return lastValidTime();
    }

    private LocalTime lastValidTime() {
        LocalTime last = null;
        for (Slot slot : slots) {
            if (slot.getTime() != null && slot.getWeight() > 0) last = slot.getTime();
        }
        return last;
    }
}
