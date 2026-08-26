package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceSchedule;
import com.portfolio.ticket.mapper.SeatMapper;
import com.portfolio.ticket.mapper.dto.GradePriceRow;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.PerformanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 공연 비교. 후보 2~3개를 나란히 놓고 날짜·장소·가격·다음 회차를 한 화면에서 본다.
 *
 * <p>담아둔 목록 자체는 서버에 없다 — 브라우저 localStorage 에 있고(fragment/comparetray),
 * 이 서비스는 그 화면이 넘겨준 id 로 표를 만들어 줄 뿐이다. 그래서 비교 주소는
 * {@code /performances/compare?ids=1,2,3} 처럼 그대로 남에게 보낼 수 있다.
 *
 * <p>조회는 JPA 로 한다. 동적 조건도 집계도 없이 id 몇 개로 엔티티를 꺼내는 일이라
 * CLAUDE.md 의 기준("애매하면 JPA")에 그대로 걸린다. 공연이 최대 3개라 공연당 회차·가격을
 * 따로 조회해도 6쿼리가 상한이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceCompareService {

    /**
     * 한 번에 비교할 수 있는 공연 수.
     *
     * <p>3 인 이유는 화면이다 — 표를 가로로 늘어놓는데, 좁은 화면(360px)에서 칸 하나가
     * 읽을 만한 최소 폭이 대략 절반이고 넓은 화면에서도 넷째 칸부터는 항목끼리 눈으로
     * 짝짓기가 어려워진다. 이슈 #2 의 문구("2~3개")와도 같다.
     */
    public static final int MAX_ITEMS = 3;

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final SeatMapper seatMapper;

    /**
     * 넘어온 id 순서를 그대로 지켜 비교표를 만든다.
     *
     * <p><b>없는 id 는 404 가 아니라 조용히 뺀다.</b> 담아둔 목록이 브라우저에 있어서,
     * 담아둔 뒤에 그 공연이 사라질 수 있다 — 모집 공고 purge({@code /api/admin/performances/purge})
     * 가 실제로 저장된 공연을 지운다. 한 칸이 사라졌다고 나머지 두 칸까지 못 보게 하면
     * 사용자는 이유도 모른 채 빈 화면을 만난다.
     */
    public List<PerformanceCompareView> compare(List<Long> ids, LocalDateTime now) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        // 중복은 순서를 지키며 접는다. 같은 공연이 두 칸을 차지하면 비교가 아니다.
        List<Long> wanted = new ArrayList<>(new LinkedHashSet<>(ids)).stream()
                .limit(MAX_ITEMS)
                .toList();

        List<PerformanceCompareView> views = new ArrayList<>();
        for (Long id : wanted) {
            Performance performance = performanceRepository.findById(id).orElse(null);
            if (performance == null) {
                continue;
            }
            List<PerformanceSchedule> schedules =
                    scheduleRepository.findByPerformanceIdOrderByShowAtAsc(id);

            // 등급별 가격은 회차 아무거나 하나로 대표한다 — 같은 공연의 모든 회차는
            // SeatGenerator 가 같은 등급별 가격으로 좌석을 만든다(상세와 같은 규칙).
            List<GradePriceRow> gradePrices = schedules.isEmpty()
                    ? List.of()
                    : seatMapper.selectGradePrices(schedules.get(0).getId());

            views.add(PerformanceCompareView.of(performance, schedules, gradePrices, now));
        }
        return views;
    }
}
