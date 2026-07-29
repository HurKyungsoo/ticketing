package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 이미 만들어진 회차의 좌석을 현재 생성 규칙으로 다시 만든다.
 *
 * 좌석 생성은 신규 공연을 수집할 때만 돌기 때문에, 생성 규칙을 바꿔도 이미 쌓인 회차에는
 * 반영되지 않는다. 규모별 구조나 좌석 수 상한 같은 변경을 기존 데이터에 적용할 때 쓴다.
 *
 * 회차 시각(showAt)은 건드리지 않는다. 상당수가 KOPIS dtguidance 에서 온 실제 공연시간이라,
 * 회차까지 다시 만들면 실측값이 대체 규칙의 근사값으로 바뀌어 오히려 정확도가 떨어진다.
 *
 * 트랜잭션은 회차 단위로 끊는다({@link ScheduleSeatRebuilder}). 수천 건을 하나로 묶으면
 * 중간에 한 건만 실패해도 전체가 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatRegenerationService {

    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleSeatRebuilder rebuilder;

    public record Result(int scheduleCount, int rebuilt, int skipped, long seatsBefore, long seatsAfter) {}

    public Result regenerateAll() {
        List<Long> scheduleIds = scheduleRepository.findAll().stream()
                .map(PerformanceSchedule::getId)
                .toList();

        int rebuilt = 0;
        int skipped = 0;
        long before = 0;
        long after = 0;

        for (Long scheduleId : scheduleIds) {
            ScheduleSeatRebuilder.Result one = rebuilder.rebuild(scheduleId);
            before += one.before();
            after += one.after();
            if (one.rebuilt()) rebuilt++; else skipped++;
        }

        log.info("좌석 재생성 완료. 회차={}, 재생성={}, 건너뜀={}, 좌석 {} -> {}",
                scheduleIds.size(), rebuilt, skipped, before, after);
        return new Result(scheduleIds.size(), rebuilt, skipped, before, after);
    }
}
