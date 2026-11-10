package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.SeatGrade;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.KopisGuidanceParser;
import com.portfolio.ticket.external.PublicDataProperties;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기본 규칙으로 만든 회차·좌석을 저장된 KOPIS 원문(실제 요일·시각, 등급별 가격)으로 다시 만든다.
 *
 * <p>상세를 놓친 채 들어온 공연은 회차가 통계 분포로 고른 임의 시각이고 좌석은 기본 가격이었다.
 * 원문이 쌓이는 대로 하루 정해진 양만큼 바꾼다.
 *
 * <p>지키는 것:
 * <ul>
 *   <li>내일 이후 회차만 다룬다. 오늘 회차는 지금 예매 중일 수 있어 두지 않는다.</li>
 *   <li>예매·선점·빈자리 알림·알림이 하나라도 걸린 회차는 그대로 둔다(사용자 기록 보존).</li>
 *   <li>원문으로 만들 회차가 하나도 없으면 아무것도 지우지 않는다 — 회차가 통째로 사라지지 않게.</li>
 * </ul>
 * 동시성 처리(락 순서)는 {@link ScheduleRebuildService#removeIfUntouched} 주석 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRegeneration {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleRebuildService rebuildService;
    private final PublicDataProperties properties;

    public record Result(int performances, int removed, int created, int kept) {}

    public Result run() {
        int limit = properties.getScheduleRebuildLimit();
        if (limit <= 0) return new Result(0, 0, 0, 0);

        LocalDate today = LocalDate.now();
        List<Long> targets = performanceRepository.findScheduleRebuildTargets(
                SourceType.KOPIS, today, PageRequest.of(0, limit));
        int removed = 0, created = 0, kept = 0, done = 0;

        for (Long performanceId : targets) {
            try {
                Performance performance = rebuildService.load(performanceId);
                if (performance == null) continue;

                LocalDate fromDate = laterOf(today.plusDays(1), performance.getStartDate());
                List<LocalDateTime> wanted = wantedShowTimes(performance, fromDate);
                if (wanted.isEmpty()) {
                    rebuildService.markSkipped(performanceId);
                    continue;
                }

                LocalDateTime from = fromDate.atStartOfDay();
                for (Long scheduleId : scheduleRepository.findIdsFrom(performanceId, from)) {
                    if (rebuildService.removeIfUntouched(scheduleId)) removed++; else kept++;
                }
                Map<SeatGrade, Integer> prices = KopisGuidanceParser.pricesByGrade(performance.getPriceGuidance());
                created += rebuildService.addMissingAndMark(performanceId, wanted, prices, from);
                done++;
            } catch (Exception e) {
                // 한 공연 때문에 나머지를 멈추지 않는다. 다시 만든 날이 안 찍혀 다음 날 다시 시도한다.
                log.warn("[회차 재생성] 건너뜀. performanceId={}, msg={}", performanceId, e.getMessage());
            }
        }
        log.info("[회차 재생성] 대상 {}건, 완료 {}건, 지운 회차 {}건, 남긴 회차 {}건, 만든 회차 {}건",
                targets.size(), done, removed, kept, created);
        return new Result(done, removed, created, kept);
    }

    private static List<LocalDateTime> wantedShowTimes(Performance performance, LocalDate from) {
        Map<DayOfWeek, List<LocalTime>> byDay = KopisGuidanceParser.showTimes(performance.getShowTimeGuidance());
        List<LocalDateTime> wanted = new ArrayList<>();
        if (byDay == null || byDay.isEmpty()) return wanted;

        for (LocalDate day = from; !day.isAfter(performance.getEndDate()); day = day.plusDays(1)) {
            List<LocalTime> times = byDay.get(day.getDayOfWeek());
            if (times == null) continue;
            for (LocalTime time : times) {
                wanted.add(LocalDateTime.of(day, time));
            }
        }
        return wanted;
    }

    private static LocalDate laterOf(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
