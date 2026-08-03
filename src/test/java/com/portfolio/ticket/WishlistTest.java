package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.WishlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 찜하기.
 *
 * <p>토글은 "누른 결과"가 아니라 <b>서버가 판단한 상태</b>를 돌려준다. 화면이 스스로 뒤집으면
 * 요청이 엇갈렸을 때 버튼 모양과 실제 저장 상태가 갈리기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WishlistTest {

    private static final Long MEMBER = 1L;
    private static final Long OTHER_MEMBER = 2L;

    @Autowired WishlistService wishlistService;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PerformanceSyncService syncService;

    private Long performanceId;

    @BeforeEach
    void setUp() {
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        performanceId = save("찜테스트공연").getId();
    }

    private Performance save(String title) {
        return performanceRepository.save(Performance.builder()
                .externalId("WISH-" + title + "-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title(title)
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    @DisplayName("토글은 호출 후의 상태를 돌려준다")
    @Test
    void toggleReturnsResultingState() {
        assertThat(wishlistService.toggle(MEMBER, performanceId)).isTrue();
        assertThat(wishlistService.isWishlisted(MEMBER, performanceId)).isTrue();

        assertThat(wishlistService.toggle(MEMBER, performanceId)).isFalse();
        assertThat(wishlistService.isWishlisted(MEMBER, performanceId)).isFalse();
    }

    @DisplayName("찜 목록은 본인 것만 나온다")
    @Test
    void listsOnlyOwnWishes() {
        Long other = save("남의공연").getId();
        wishlistService.toggle(MEMBER, performanceId);
        wishlistService.toggle(OTHER_MEMBER, other);

        assertThat(wishlistService.findMyWishlist(MEMBER))
                .hasSize(1)
                .allSatisfy(w -> assertThat(w.getPerformance().getTitle()).isEqualTo("찜테스트공연"));
    }

    @DisplayName("없는 공연은 찜할 수 없다")
    @Test
    void rejectsUnknownPerformance() {
        assertThatThrownBy(() -> wishlistService.toggle(MEMBER, 999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * 버튼을 따닥 누르면 요청이 둘 나가고, 둘 다 "아직 없음"을 읽은 뒤 각자 insert 하려 든다.
     * 존재 확인만으로는 못 막는 검사-후-삽입 경쟁이라 최종 판정은 유니크 제약에 맡긴다.
     * <b>진 쪽도 실패가 아니어야 한다</b> — 사용자가 원한 결과("찜해둔 상태")는 어느 쪽이
     * 이기든 똑같이 이뤄지기 때문이다.
     */
    @DisplayName("동시에 여러 번 찜해도 한 건만 남고 예외도 새지 않는다")
    @Test
    void concurrentTogglesLeaveSingleRow() throws InterruptedException {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    wishlistService.toggle(MEMBER, performanceId);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(errors.get()).as("중복 요청이 예외로 새면 안 된다").isZero();
        // 짝수 번 토글되면 0건, 홀수면 1건. 어느 쪽이든 2건 이상은 제약 위반이다.
        assertThat(wishlistRepository.count()).isLessThanOrEqualTo(1);
    }

    /**
     * 찜은 공연을 FK 로 참조한다. 정리하지 않으면 모집 공고 제거가 제약 위반으로 막힌다 —
     * 아무도 찜하지 않았을 때만 통과하므로 평소엔 드러나지 않는다.
     */
    @DisplayName("찜한 공연이 수집 대상에서 빠져도 삭제가 막히지 않는다")
    @Test
    void purgeWorksEvenWhenWishlisted() {
        Long noticeId = save("2026 블로그 서포터즈 2기 모집").getId();
        wishlistService.toggle(MEMBER, noticeId);
        assertThat(wishlistRepository.count()).isEqualTo(1);

        assertThat(syncService.purgeNonPerformances()).isEqualTo(1);
        assertThat(performanceRepository.findById(noticeId)).isEmpty();
        assertThat(wishlistRepository.count()).as("가리킬 공연이 사라졌으니 찜도 남지 않는다").isZero();
    }
}
