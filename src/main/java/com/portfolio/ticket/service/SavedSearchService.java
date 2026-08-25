package com.portfolio.ticket.service;

import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SavedSearch;
import com.portfolio.ticket.repository.SavedSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SavedSearchService {

    /**
     * 회원 한 명이 저장할 수 있는 검색 조건 수. 무제한으로 두면 배치(NotificationService.
     * matchSavedSearches)가 공연 하나 등록될 때마다 훑는 행이 한 회원 때문에 계속 늘어난다 —
     * 위시리스트·취소표 구독처럼 "정말로 쓰는 것"만 남게 하는 자연스러운 상한이 없어서
     * 직접 둔다. 검색 조건 축이 4개(장르·지역·월·검색어)뿐이라 20개면 조합을 거의 다
     * 커버하고도 남는다.
     */
    private static final int MAX_SAVED_SEARCHES_PER_MEMBER = 20;

    private final SavedSearchRepository savedSearchRepository;

    /**
     * @throws TooManySavedSearchesException 이미 상한에 도달한 경우
     */
    @Transactional
    public SavedSearch save(Long memberId, PerformanceCategory category, String region,
                             Integer month, String keyword) {
        // count-then-insert 라 동시에 두 요청이 들어오면 상한을 하나 넘길 수 있다. 이 상한은
        // 안전장치가 아니라 "무한정 쌓이지 않게 하는" 정도의 목적이라, 그 정도 오차는 감수한다
        // — 좌석 선점처럼 정합성이 걸린 자원이었다면 유니크 제약이나 락으로 막았을 것이다.
        if (savedSearchRepository.countByMemberId(memberId) >= MAX_SAVED_SEARCHES_PER_MEMBER) {
            throw new TooManySavedSearchesException(
                    "저장할 수 있는 검색 조건은 최대 " + MAX_SAVED_SEARCHES_PER_MEMBER + "개입니다.");
        }
        String label = SavedSearch.labelFrom(category, region, month, keyword);
        return savedSearchRepository.save(SavedSearch.builder()
                .memberId(memberId)
                .label(label)
                .category(category == null ? null : category.name())
                .region(region)
                .month(month)
                .keyword(keyword)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<SavedSearch> findMine(Long memberId) {
        return savedSearchRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /** @return 실제로 지웠으면 true. 남의 id 거나 이미 없는 id 면 false — 존재 여부가 새어 나가지 않는다. */
    @Transactional
    public boolean delete(Long id, Long memberId) {
        return savedSearchRepository.deleteByIdAndMemberId(id, memberId) > 0;
    }
}
