package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.SeatStatus;
import com.portfolio.ticket.external.PublicDataProperties;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import com.portfolio.ticket.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 지난 회차의 빈 좌석을 지운다.
 *
 * <p>회차마다 좌석을 미리 다 만들어 두는 구조라, 공연이 지나도 팔리지 않은 좌석이 그대로 쌓였다.
 * 좌석 테이블 1,160만 행 중 약 74%가 이미 지난 회차의 빈 좌석이었다. 화면에 다시 나올 일이 없다.
 *
 * <p>지키는 것:
 * <ul>
 *   <li>지난 지 하루가 넘은 회차만 다룬다.</li>
 *   <li>빈 좌석(AVAILABLE) 중 예매와 이어지지 않은 것만 지운다. 판매된 좌석과 예매가 가리키는 좌석은
 *       예매 내역·결제 화면이 쓰므로 남긴다. 회차 행도 남긴다(예매가 회차를 가리킨다).</li>
 *   <li>회차 하나씩 따로 커밋하고, 하루 쓸 시간을 정해 두고 넘으면 다음 날 이어서 한다.</li>
 * </ul>
 * 지난 회차는 예매가 마감돼 좌석을 잡을 수 없으므로 좌석 선점과 경쟁하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PastSeatCleanup {

    private static final Duration KEEP_AFTER_SHOW = Duration.ofDays(1);
    private static final int PAGE = 500;

    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatRepository seatRepository;
    private final TransactionTemplate transactionTemplate;
    private final PublicDataProperties properties;

    public record Result(int schedules, long seats, boolean finished) {}

    public Result run() {
        long budgetMinutes = properties.getPastSeatCleanupMinutes();
        if (budgetMinutes <= 0) return new Result(0, 0, true);

        long startedAt = System.nanoTime();
        long deadline = startedAt + Duration.ofMinutes(budgetMinutes).toNanos();
        LocalDateTime cutoff = LocalDateTime.now().minus(KEEP_AFTER_SHOW);
        int schedules = 0;
        long seats = 0;
        boolean finished = false;

        while (System.nanoTime() < deadline) {
            List<Long> ids = scheduleRepository.findPastWithUnusedSeats(
                    cutoff, SeatStatus.AVAILABLE, PageRequest.of(0, PAGE));
            if (ids.isEmpty()) {
                finished = true;
                break;
            }
            for (Long id : ids) {
                if (System.nanoTime() >= deadline) break;
                Integer deleted = transactionTemplate.execute(
                        status -> seatRepository.deleteUnusedBySchedule(id, SeatStatus.AVAILABLE));
                seats += deleted == null ? 0 : deleted;
                schedules++;
            }
        }
        log.info("[지난 회차 좌석 정리] 회차 {}건, 좌석 {}건 삭제, {}초, {}",
                schedules, seats, Duration.ofNanos(System.nanoTime() - startedAt).toSeconds(),
                finished ? "남은 작업 없음" : "남은 작업은 다음 날 이어서");
        return new Result(schedules, seats, finished);
    }
}
