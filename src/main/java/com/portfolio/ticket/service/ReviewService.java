package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.Review;
import com.portfolio.ticket.mapper.ReviewMapper;
import com.portfolio.ticket.mapper.dto.ReviewStatRow;
import com.portfolio.ticket.repository.PerformanceRepository;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관람평.
 *
 * <p><b>이 서비스의 핵심은 저장이 아니라 자격 판정이다.</b> 예매 서비스에서 관람평의 값어치는
 * "그 공연을 실제로 본 사람이 썼다"는 데서 나온다. 누구나 쓸 수 있으면 그냥 댓글이다.
 *
 * <p>자격은 둘 다 만족해야 한다:
 * <ol>
 *   <li>그 공연에 <b>확정된</b> 예매가 있을 것 — 선점만 하고 결제 안 한 건, 취소·만료된 건은 안 된다.
 *   <li>그 예매의 <b>관람일이 지났을 것</b> — 아직 안 본 공연의 관람평은 관람평이 아니다.
 * </ol>
 *
 * <p>취소한 예매를 빼는 건 일부러다. 돈을 냈다가 물렀으면 안 본 것이고, 취소분까지 인정하면
 * "예매 → 관람평 → 취소"로 평점만 남기고 빠져나갈 수 있다.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewMapper reviewMapper;
    private final ReservationRepository reservationRepository;
    private final PerformanceRepository performanceRepository;

    /**
     * 관람평을 쓴다. 이미 썼으면 고친다 — 화면에서 두 경우를 나눠 보여주지만, 그 사이에
     * 다른 탭에서 먼저 썼을 수 있으므로 서버는 한 경로로 받아 같은 결과를 낸다.
     */
    @Transactional
    public Review write(Long memberId, String nickname, Long performanceId, int rating, String content) {
        validate(rating, content);
        if (!canWrite(memberId, performanceId, LocalDateTime.now())) {
            throw new ForbiddenException("관람일이 지난 예매 확정 건만 관람평을 쓸 수 있습니다.");
        }

        String trimmed = content.trim();
        return reviewRepository.findByMemberIdAndPerformanceId(memberId, performanceId)
                .map(existing -> {
                    existing.edit(rating, trimmed);
                    return existing;
                })
                .orElseGet(() -> save(memberId, nickname, performanceId, rating, trimmed));
    }

    private Review save(Long memberId, String nickname, Long performanceId, int rating, String content) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));
        try {
            return reviewRepository.saveAndFlush(Review.builder()
                    .memberId(memberId)
                    .performance(performance)
                    .nickname(nickname)
                    .rating(rating)
                    .content(content)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 위 조회와 저장 사이에 다른 요청이 먼저 넣은 경우(따닥 제출). 유니크 제약이
            // 막아준 것이므로 실패가 아니라 "이미 있음"으로 보고 그 건을 고친다 —
            // 사용자가 원한 결과(내 관람평이 이 내용으로 남는다)는 어느 쪽이 이기든 같다.
            Review existing = reviewRepository.findByMemberIdAndPerformanceId(memberId, performanceId)
                    .orElseThrow(() -> e);
            existing.edit(rating, content);
            return existing;
        }
    }

    /** 내 관람평만 지울 수 있다. 남의 것은 조회 단계에서 걸러져 아무 일도 일어나지 않는다. */
    @Transactional
    public void delete(Long reviewId, Long memberId) {
        reviewRepository.findById(reviewId)
                .filter(r -> r.isWrittenBy(memberId))
                .ifPresent(reviewRepository::delete);
    }

    /**
     * 쓸 자격이 있는지. 화면이 작성 폼을 보여줄지 정하는 데도 쓴다 — 자격도 없는 사람에게
     * 폼을 띄우고 제출 시점에 거절하면, 다 쓰고 나서야 못 쓴다는 걸 알게 된다.
     *
     * @param now 관람일 경과 판정 기준. 테스트에서 고정하려고 파라미터로 받는다.
     */
    @Transactional(readOnly = true)
    public boolean canWrite(Long memberId, Long performanceId, LocalDateTime now) {
        if (memberId == null) {
            return false;
        }
        return reservationRepository.findWithScheduleByMemberIdOrderByCreatedAtDesc(memberId).stream()
                .filter(r -> r.getStatus() == ReservationStatus.CONFIRMED)
                .filter(r -> r.getSchedule().getPerformance().getId().equals(performanceId))
                .anyMatch(r -> r.getSchedule().isPast(now));
    }

    @Transactional(readOnly = true)
    public List<Review> findByPerformance(Long performanceId) {
        return reviewRepository.findByPerformanceIdOrderByCreatedAtDesc(performanceId);
    }

    /** 내가 이 공연에 쓴 관람평. 화면이 "쓰기"와 "고치기" 중 무엇을 보여줄지 정한다. */
    @Transactional(readOnly = true)
    public Review findMine(Long memberId, Long performanceId) {
        return memberId == null ? null
                : reviewRepository.findByMemberIdAndPerformanceId(memberId, performanceId).orElse(null);
    }

    /** 요약 카드용 개수·평균. 관람평이 없으면 count 0, average null 이다. */
    @Transactional(readOnly = true)
    public ReviewStatRow stat(Long performanceId) {
        return reviewMapper.selectReviewStat(performanceId);
    }

    /**
     * 별점 범위와 본문 길이. 화면이 이미 막지만(maxlength·min·max) 그건 거들 뿐이라
     * 서버가 다시 본다 — 폼은 우회할 수 있고, 범위를 벗어난 별점은 평균을 조용히 망친다.
     */
    private void validate(int rating, String content) {
        if (rating < Review.MIN_RATING || rating > Review.MAX_RATING) {
            throw new IllegalArgumentException("별점은 1~5 사이여야 합니다.");
        }
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("관람평 내용을 입력해 주세요.");
        }
        if (content.trim().length() > Review.MAX_CONTENT) {
            throw new IllegalArgumentException("관람평은 " + Review.MAX_CONTENT + "자까지 쓸 수 있습니다.");
        }
    }
}
