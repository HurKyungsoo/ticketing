package com.portfolio.ticket;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.MemberRole;
import com.portfolio.ticket.domain.Notification;
import com.portfolio.ticket.domain.NotificationType;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.external.PerformanceSyncService;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.NotificationService;
import com.portfolio.ticket.service.WishlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 찜한 공연에 예매 가능한 회차가 열렸을 때의 알림.
 *
 * <p>이 기능의 어려운 부분은 발송이 아니라 <b>안 보내는 조건</b>이다. 동기화는 매일 돌고
 * 찜은 계속 쌓이므로, 조건을 느슨하게 잡으면 같은 알림이 매일 반복되어 기능 자체가 소음이 된다.
 * 그래서 "예매 가능한 회차가 0 개였다가 생겼을 때"로 좁히고, 그마저도 유니크 제약으로
 * 한 공연당 한 번만 나가게 막았다. 아래 테스트는 대부분 그 두 가지를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationTest {

    private static final Long MEMBER = 1L;
    private static final Long OTHER_MEMBER = 2L;

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistService wishlistService;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PerformanceSyncService syncService;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
    }

    /** 회차가 하나도 없는 공연. topUpStaleSchedules 가 잡아가는 상태다. */
    private Performance saveWithoutSchedules(String title) {
        return performanceRepository.save(Performance.builder()
                .externalId("NOTI-" + title + "-" + System.nanoTime())
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

    @DisplayName("찜한 공연에 예매 가능한 회차가 열리면 알림이 생긴다")
    @Test
    void notifiesWhenSchedulesOpen() {
        Long performanceId = saveWithoutSchedules("회차없던공연").getId();
        wishlistService.toggle(MEMBER, performanceId);

        assertThat(syncService.topUpStaleSchedules()).isEqualTo(1);

        List<Notification> mine = notificationService.findMine(MEMBER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getType()).isEqualTo(NotificationType.SCHEDULE_OPENED);
        assertThat(mine.get(0).getPerformance().getId()).isEqualTo(performanceId);
        assertThat(mine.get(0).isUnread()).as("갓 만든 알림은 안 읽음이다").isTrue();
        assertThat(notificationService.countUnread(MEMBER)).isEqualTo(1);
    }

    @DisplayName("찜하지 않은 사람에게는 알림이 가지 않는다")
    @Test
    void notifiesOnlyWishers() {
        Long performanceId = saveWithoutSchedules("찜한사람만").getId();
        wishlistService.toggle(MEMBER, performanceId);

        syncService.topUpStaleSchedules();

        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
        assertThat(notificationService.findMine(OTHER_MEMBER)).isEmpty();
    }

    @DisplayName("아무도 찜하지 않았으면 알림을 만들지 않는다")
    @Test
    void createsNothingWithoutWishes() {
        saveWithoutSchedules("아무도안찜");

        assertThat(syncService.topUpStaleSchedules()).isEqualTo(1);

        assertThat(notificationRepository.count()).isZero();
    }

    /**
     * 이 기능이 스팸이 되는 가장 흔한 경로. 동기화는 매일 도는데, 두 번째 실행에서 다시
     * 알림이 만들어지면 찜해 둔 공연 수만큼 매일 알림이 쌓인다.
     *
     * <p>두 번째 실행에서는 애초에 예매 가능한 회차가 이미 있으므로 이벤트 자체가 발행되지
     * 않는 게 정상이다. 그래도 결과 건수로 확인하는 이유는, 나중에 발행 조건을 손대더라도
     * "한 번만 간다"는 사용자에게 보이는 약속은 그대로여야 하기 때문이다(유니크 제약이 받친다).
     */
    @DisplayName("동기화를 다시 돌려도 알림이 중복되지 않는다")
    @Test
    void doesNotDuplicateOnRepeatedSync() {
        Long performanceId = saveWithoutSchedules("반복동기화").getId();
        wishlistService.toggle(MEMBER, performanceId);

        syncService.topUpStaleSchedules();
        syncService.topUpStaleSchedules();
        syncService.topUpStaleSchedules();

        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
    }

    /**
     * 이미 예매할 수 있는 공연은 알리지 않는다. 사용자가 언제든 들어가서 고를 수 있으므로
     * "열렸다"는 알림이 정보를 주지 않고, 회차가 늘어날 때마다 울리면 소음이 된다.
     */
    @DisplayName("이미 예매 가능한 회차가 있던 공연은 알리지 않는다")
    @Test
    void doesNotNotifyWhenAlreadyBookable() {
        Long performanceId = saveWithoutSchedules("이미예매가능").getId();
        wishlistService.toggle(MEMBER, performanceId);

        // 1회차: 0 → N 이라 알림이 간다.
        syncService.topUpStaleSchedules();
        assertThat(notificationService.findMine(MEMBER)).hasSize(1);
        notificationRepository.deleteAll();

        // 이제 예매 가능한 회차가 있는 상태. 여기서 또 보충돼도 알림은 없어야 한다.
        syncService.topUpStaleSchedules();
        assertThat(notificationRepository.count()).isZero();
    }

    @DisplayName("알림을 읽으면 안 읽은 수가 줄고, 다시 읽어도 최초 시각이 유지된다")
    @Test
    void marksRead() {
        Long performanceId = saveWithoutSchedules("읽음처리").getId();
        wishlistService.toggle(MEMBER, performanceId);
        syncService.topUpStaleSchedules();

        Notification created = notificationService.findMine(MEMBER).get(0);
        assertThat(notificationService.markRead(created.getId(), MEMBER)).contains(performanceId);
        assertThat(notificationService.countUnread(MEMBER)).isZero();

        var readAt = notificationService.findMine(MEMBER).get(0).getReadAt();
        notificationService.markRead(created.getId(), MEMBER);
        assertThat(notificationService.findMine(MEMBER).get(0).getReadAt())
                .as("다시 눌러도 최초로 읽은 시각이 바뀌면 안 된다").isEqualTo(readAt);
    }

    @DisplayName("남의 알림은 읽음 처리되지 않는다")
    @Test
    void cannotReadOthersNotification() {
        Long performanceId = saveWithoutSchedules("남의알림").getId();
        wishlistService.toggle(MEMBER, performanceId);
        syncService.topUpStaleSchedules();
        Long notificationId = notificationService.findMine(MEMBER).get(0).getId();

        assertThat(notificationService.markRead(notificationId, OTHER_MEMBER)).isEmpty();
        assertThat(notificationService.countUnread(MEMBER))
                .as("남이 눌러도 주인의 안 읽음 상태는 그대로다").isEqualTo(1);
    }

    /**
     * 알림은 공연을 FK 로 참조한다. 정리하지 않으면 모집 공고 제거가 제약 위반으로 막힌다 —
     * 찜에서 똑같이 겪었던 문제라(WishlistTest.purgeWorksEvenWhenWishlisted) 같이 검증한다.
     */
    @DisplayName("알림이 달린 공연이 수집 대상에서 빠져도 삭제가 막히지 않는다")
    @Test
    void purgeWorksEvenWhenNotified() {
        Long noticeId = saveWithoutSchedules("2026 블로그 서포터즈 2기 모집").getId();
        wishlistService.toggle(MEMBER, noticeId);
        syncService.topUpStaleSchedules();
        assertThat(notificationRepository.count()).isEqualTo(1);

        assertThat(syncService.purgeNonPerformances()).isEqualTo(1);
        assertThat(performanceRepository.findById(noticeId)).isEmpty();
        assertThat(notificationRepository.count())
                .as("가리킬 공연이 사라졌으니 알림도 남지 않는다").isZero();
    }

    /* ------------------------------------------------------------------
     *  읽음 처리는 GET 이면 안 된다
     * ------------------------------------------------------------------ */

    /**
     * 처음엔 알림 행이 {@code <a href="/mypage/notifications/{id}">} 였고 GET 에서 읽음 처리를 했다.
     * 로컬에서 목록을 열어 두기만 했는데 알림이 읽음으로 바뀌었다 — 크롬이 곧 눌릴 것 같은
     * 링크를 미리 당겨오기 때문이다(link prefetch). 크롤러·백그라운드 탭 열기·링크 검사도 같다.
     *
     * <p>즉 <b>사용자가 보지도 않은 알림이 읽음이 된다</b>. 안 읽은 알림이 조용히 사라지는
     * 종류의 버그라 화면만 보고는 알아채기 어렵다. 그래서 "읽음 처리 경로에 GET 이 없다"를
     * 테스트로 못 박는다 — 나중에 편의로 GET 을 다시 열면 여기서 걸린다.
     */
    @DisplayName("읽음 처리 경로는 GET 을 받지 않는다 (링크 미리 가져오기로 읽음 처리되면 안 된다)")
    @Test
    void readEndpointRejectsGet() throws Exception {
        Long performanceId = saveWithoutSchedules("GET방지").getId();
        CustomUserDetails principal = principalFor("noti-get");
        wishlistService.toggle(principal.getMemberId(), performanceId);
        syncService.topUpStaleSchedules();
        Long notificationId = notificationService.findMine(principal.getMemberId()).get(0).getId();

        // 4xx 면 된다. 405/404 중 무엇인지까지는 안 박는다 — PageExceptionHandler 가 화면용
        // 에러 페이지로 바꾸면서 코드가 달라질 수 있고, 여기서 지켜야 할 건 "GET 은 안 통한다"다.
        mockMvc.perform(get("/mypage/notifications/{id}/read", notificationId).with(user(principal)))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/mypage/notifications/{id}", notificationId).with(user(principal)))
                .andExpect(status().is4xxClientError());

        assertThat(notificationService.countUnread(principal.getMemberId()))
                .as("GET 으로는 어떤 경로로도 읽음이 되면 안 된다").isEqualTo(1);
    }

    @DisplayName("POST 로 읽으면 읽음 처리되고 공연 상세로 보낸다")
    @Test
    void readEndpointAcceptsPost() throws Exception {
        Long performanceId = saveWithoutSchedules("POST읽음").getId();
        CustomUserDetails principal = principalFor("noti-post");
        wishlistService.toggle(principal.getMemberId(), performanceId);
        syncService.topUpStaleSchedules();
        Long notificationId = notificationService.findMine(principal.getMemberId()).get(0).getId();

        mockMvc.perform(post("/mypage/notifications/{id}/read", notificationId)
                        .with(user(principal)).with(csrf()))
                .andExpect(redirectedUrl("/performances/" + performanceId));

        assertThat(notificationService.countUnread(principal.getMemberId())).isZero();
    }

    private CustomUserDetails principalFor(String loginId) {
        return new CustomUserDetails(memberRepository.save(Member.builder()
                .loginId(loginId + "-" + System.nanoTime())
                .password("{noop}pw")
                .nickname(loginId)
                .role(MemberRole.USER)
                .createdAt(LocalDateTime.now())
                .build()));
    }
}
