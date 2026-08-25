package com.portfolio.ticket;

import com.portfolio.ticket.domain.*;
import com.portfolio.ticket.external.ExternalPerformance;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.SavedSearchService;
import com.portfolio.ticket.service.TooManySavedSearchesException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 이슈 #2. 저장한 검색 조건과 새 공연 등록의 연결 — 서비스 계층(한도·소유권)과
 * 배치→알림 경로(PerformanceSyncService.sync → PerformanceRegisteredEvent →
 * NotificationService.onPerformanceRegistered)를 같이 본다.
 *
 * <p>매칭 규칙 자체(SavedSearch.matches)는 DB 없이 SavedSearchMatchTest 에서 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SavedSearchNotificationTest {

    @Autowired MockMvc mockMvc;
    @Autowired PerformanceSyncService syncService;
    @Autowired SavedSearchService savedSearchService;
    @Autowired SavedSearchRepository savedSearchRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Long memberId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        savedSearchRepository.deleteAll();
        memberRepository.deleteAll();

        memberId = memberRepository.save(Member.builder()
                .loginId("savedsearcher")
                .password(passwordEncoder.encode("password"))
                .nickname("저장검색테스트")
                .email("saved@example.com")
                .createdAt(LocalDateTime.now())
                .build()).getId();
    }

    private CustomUserDetails principal() {
        return new CustomUserDetails(memberRepository.findByLoginId("savedsearcher").orElseThrow());
    }

    private ExternalPerformance external(String externalId, String genre, String region) {
        return ExternalPerformance.builder()
                .externalId(externalId)
                .sourceType(SourceType.KOPIS)
                .title("배치 테스트 공연")
                .genre(genre)
                .venue("테스트홀")
                .region(region)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(20))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build();
    }

    @DisplayName("새로 등록된 공연이 저장 검색과 맞으면 알림이 생긴다")
    @Test
    void notifiesOnMatchingNewPerformance() {
        savedSearchService.save(memberId, PerformanceCategory.MUSICAL, "서울특별시", null, null);

        syncService.sync(List.of(external("SS-MATCH-1", "뮤지컬", "서울특별시")));

        Performance saved = performanceRepository.findByExternalId("SS-MATCH-1").orElseThrow();
        assertThat(notificationRepository.existsByMemberIdAndPerformanceIdAndType(
                memberId, saved.getId(), NotificationType.SAVED_SEARCH_MATCH)).isTrue();
    }

    @DisplayName("맞지 않는 공연이 등록돼도 알림이 안 생긴다")
    @Test
    void doesNotNotifyOnNonMatchingPerformance() {
        savedSearchService.save(memberId, PerformanceCategory.MUSICAL, "서울특별시", null, null);

        syncService.sync(List.of(external("SS-NOMATCH-1", "연극", "부산광역시")));

        Performance saved = performanceRepository.findByExternalId("SS-NOMATCH-1").orElseThrow();
        assertThat(notificationRepository.existsByMemberIdAndPerformanceIdAndType(
                memberId, saved.getId(), NotificationType.SAVED_SEARCH_MATCH)).isFalse();
    }

    /**
     * 이 기능의 핵심 불변식. 이미 있는 공연이 회차만 보충되는 경로(topUpSchedules)는
     * PerformanceRegisteredEvent 를 안 낸다 — 등록 후 몇 달간 회차가 보충될 때마다
     * 같은 공연으로 알림이 반복되면 안 된다(PerformanceRegisteredEvent 주석).
     */
    @DisplayName("이미 있는 공연을 다시 동기화해도(회차 보충) 두 번째 알림은 안 생긴다")
    @Test
    void doesNotRenotifyOnResync() {
        // 저장 검색 없이 먼저 등록 - 알림 여부와 무관하게 공연부터 만든다.
        syncService.sync(List.of(external("SS-RESYNC-1", "뮤지컬", "서울특별시")));

        // 이제 저장 검색을 만든다. 이미 있는 공연이라 이 시점 이후 재동기화는 매칭 대상이
        // 될 조건을 갖췄지만, "새로 등록"이 아니므로 이벤트 자체가 안 나가야 한다.
        savedSearchService.save(memberId, PerformanceCategory.MUSICAL, "서울특별시", null, null);

        syncService.sync(List.of(external("SS-RESYNC-1", "뮤지컬", "서울특별시")));

        Performance saved = performanceRepository.findByExternalId("SS-RESYNC-1").orElseThrow();
        assertThat(notificationRepository.existsByMemberIdAndPerformanceIdAndType(
                memberId, saved.getId(), NotificationType.SAVED_SEARCH_MATCH)).isFalse();
    }

    @DisplayName("저장 한도를 넘으면 예외가 난다")
    @Test
    void throwsWhenLimitExceeded() {
        for (int i = 0; i < 20; i++) {
            savedSearchService.save(memberId, null, null, null, "검색어" + i);
        }

        assertThatThrownBy(() -> savedSearchService.save(memberId, null, null, null, "스물한번째"))
                .isInstanceOf(TooManySavedSearchesException.class);
        assertThat(savedSearchRepository.countByMemberId(memberId)).isEqualTo(20);
    }

    @DisplayName("본인 것만 지울 수 있다")
    @Test
    void deleteOnlyOwn() {
        Long otherMemberId = memberRepository.save(Member.builder()
                .loginId("other")
                .password(passwordEncoder.encode("password"))
                .nickname("다른회원")
                .createdAt(LocalDateTime.now())
                .build()).getId();
        SavedSearch mine = savedSearchService.save(memberId, null, null, null, "내검색");

        boolean deletedByOther = savedSearchService.delete(mine.getId(), otherMemberId);
        boolean deletedByMe = savedSearchService.delete(mine.getId(), memberId);

        assertThat(deletedByOther).as("남의 요청으로는 안 지워져야 한다").isFalse();
        assertThat(deletedByMe).as("본인 요청은 지워져야 한다").isTrue();
    }

    @DisplayName("저장 API - 'all'/빈 값은 전체 조건(null)로 정리돼 저장된다")
    @Test
    void apiNormalizesAllSentinel() throws Exception {
        mockMvc.perform(post("/api/saved-searches").with(user(principal())).with(csrf())
                        .contentType("application/json")
                        .content("{\"category\":\"all\",\"region\":\"\",\"month\":null,\"keyword\":\"오페라의유령\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("'오페라의유령'"));

        List<SavedSearch> mine = savedSearchService.findMine(memberId);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getCategory()).isNull();
        assertThat(mine.get(0).getRegion()).isNull();
        assertThat(mine.get(0).getKeyword()).isEqualTo("오페라의유령");
    }

    @DisplayName("마이페이지에 저장한 검색 라벨이 렌더링된다")
    @Test
    void savedSearchesPageRendersLabel() throws Exception {
        savedSearchService.save(memberId, PerformanceCategory.MUSICAL, "서울특별시", 9, null);

        mockMvc.perform(get("/mypage/saved-searches").with(user(principal())))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(html).contains("서울특별시 · 뮤지컬 · 9월");
                });
    }

    @DisplayName("목록 화면의 「이 조건 저장」 버튼은 로그인 상태에서만 그려진다")
    @Test
    void saveSearchButtonOnlyForAuthenticatedUsers() throws Exception {
        String anonymousHtml = mockMvc.perform(get("/performances").param("status", "ongoing"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(anonymousHtml).doesNotContain("id=\"saveSearchBtn\"");

        String authedHtml = mockMvc.perform(get("/performances").param("status", "ongoing").with(user(principal())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(authedHtml).contains("id=\"saveSearchBtn\"");
    }
}
