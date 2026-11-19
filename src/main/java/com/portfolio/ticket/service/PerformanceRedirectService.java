package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceRedirect;
import com.portfolio.ticket.repository.PerformanceRedirectRepository;
import com.portfolio.ticket.repository.PerformanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 합쳐서 지운 공연의 옛 번호를 남긴 공연으로 잇는다({@link PerformanceRedirect}). */
@Service
@RequiredArgsConstructor
public class PerformanceRedirectService {

    private final PerformanceRedirectRepository redirectRepository;
    private final PerformanceRepository performanceRepository;

    /**
     * 옛 번호가 지금 가리키는 공연. 기록이 없거나 남긴 공연도 이미 없으면 비어 있다 —
     * 없는 공연으로 보내면 404 를 한 번 더 거칠 뿐이다.
     */
    @Transactional(readOnly = true)
    public Optional<Long> movedTo(Long performanceId) {
        return redirectRepository.findById(performanceId)
                .map(PerformanceRedirect::getToId)
                .filter(performanceRepository::existsById);
    }
}
