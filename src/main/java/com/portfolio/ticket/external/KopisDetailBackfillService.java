package com.portfolio.ticket.external;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 상세 보충의 DB 쪽. 외부 호출은 하지 않는다 — 호출은 트랜잭션 밖에서 {@link KopisDetailBackfill} 이 한다.
 *
 * <p>공연 정보만 바꾸고 회차·좌석은 건드리지 않는다. 기존 회차는 기본 규칙으로 만든 것이라
 * 실제 공연 시간과 다를 수 있지만, 예매와 얽혀 있어 따로 다룬다.
 */
@Service
@RequiredArgsConstructor
public class KopisDetailBackfillService {

    private final PerformanceRepository performanceRepository;
    private final PerformanceCategoryResolver categoryResolver;

    @Transactional(readOnly = true)
    public List<ExternalPerformance> findTargets(int limit) {
        return performanceRepository
                .findDetailBackfillTargets(SourceType.KOPIS, LocalDate.now(), PageRequest.of(0, limit))
                .stream()
                .map(KopisDetailBackfillService::toBasic)
                .toList();
    }

    /** 받은 상세로 공연 정보를 채우고 시도한 날을 남긴다. 공연이 그 사이 지워졌으면 아무것도 하지 않는다. */
    @Transactional
    public void apply(ExternalPerformance detailed) {
        performanceRepository.findByExternalId(detailed.getExternalId()).ifPresent(performance -> {
            performance.updateFromExternal(
                    detailed.getTitle(), detailed.getGenre(), detailed.getVenue(),
                    detailed.getVenueFacilityId(), detailed.getVenueHallId(),
                    detailed.getAddress(), detailed.getLatitude(), detailed.getLongitude(),
                    detailed.getStartDate(), detailed.getEndDate(),
                    detailed.getPosterUrl(), detailed.getDescription(),
                    detailed.getAgeLimit(), detailed.getRunningTime(), detailed.getCastMembers(),
                    detailed.getTotalSeatCount(), detailed.getBasePrice(),
                    detailed.getSourceType(), categoryResolver.resolve(detailed.getGenre()),
                    detailed.getRegion(), detailed.getBookingLinks());
            performance.markDetailFetched(LocalDate.now());
        });
    }

    /** 상세 응답을 입힐 바탕. 상세에 없는 값(제목, 기간 등)은 저장된 값을 그대로 쓴다. */
    private static ExternalPerformance toBasic(Performance p) {
        return ExternalPerformance.builder()
                .externalId(p.getExternalId())
                .sourceType(p.getSourceType())
                .title(p.getTitle())
                .genre(p.getGenre())
                .venue(p.getVenue())
                .address(p.getAddress())
                .region(p.getRegion())
                .latitude(p.getLatitude())
                .longitude(p.getLongitude())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .posterUrl(p.getPosterUrl())
                .totalSeatCount(p.getTotalSeatCount())
                .basePrice(p.getBasePrice())
                // 엔티티의 컬렉션을 그대로 넘기면 트랜잭션 밖에서 지연 로딩이 터지고, 상세가 비어
                // 이 값이 그대로 돌아오면 updateFromExternal 이 같은 컬렉션을 비운 뒤 채워 넣게 된다.
                .bookingLinks(new ArrayList<>(p.getBookingLinks()))
                .build();
    }
}
