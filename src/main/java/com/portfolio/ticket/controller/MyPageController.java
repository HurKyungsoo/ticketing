package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Notification;
import com.portfolio.ticket.domain.NotificationType;
import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.NotificationService;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.ShareMetaView;
import com.portfolio.ticket.service.WishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class MyPageController {

    private final ReservationService reservationService;
    private final WishlistService wishlistService;
    private final NotificationService notificationService;
    private final ShareMetaView shareMetaView;

    @GetMapping("/mypage/reservations")
    public String reservations(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        List<Reservation> reservations = reservationService.findMyReservations(principal.getMemberId());
        model.addAttribute("reservations", reservations);
        // 결제 가능 여부(선점 만료) 판단을 템플릿에서 하기 위해 기준 시각을 그대로 넘긴다.
        model.addAttribute("now", LocalDateTime.now());
        // 예매 건 팝업의 「공유」. 예매마다 공연이 다르므로 건별로 만들어 둔다.
        // 새 조회는 없다 — 공연·회차는 목록을 그리느라 이미 가져온 것이다.
        model.addAttribute("shareMetas", reservations.stream().collect(Collectors.toMap(
                Reservation::getReservationNo,
                r -> shareMetaView.shareMetaOf(r.getSchedule().getPerformance(), r.getSchedule().getShowAt()),
                (a, b) -> a,
                LinkedHashMap::new)));
        shareMetaView.addKakaoJsKey(model);
        addUnreadCount(principal, model);
        return "member/reservations";
    }

    @GetMapping("/mypage/wishlist")
    public String wishlist(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        List<Wishlist> wishes = wishlistService.findMyWishlist(principal.getMemberId());
        model.addAttribute("wishes", wishes);
        addUnreadCount(principal, model);
        return "member/wishlist";
    }

    @GetMapping("/mypage/notifications")
    public String notifications(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        List<Notification> notifications = notificationService.findMine(principal.getMemberId());
        model.addAttribute("notifications", notifications);
        addUnreadCount(principal, model);
        return "member/notifications";
    }

    /**
     * 알림을 눌렀을 때. 읽음 처리하고 공연 상세로 보낸다.
     *
     * <p><b>GET 이 아니라 POST 인 이유.</b> 처음엔 알림 행을 {@code <a href="/mypage/notifications/1">}
     * 로 만들고 GET 에서 읽음 처리했는데, 목록을 열어 두기만 해도 알림이 읽음으로 바뀌었다 —
     * 크롬이 곧 눌릴 것 같은 링크를 미리 당겨오기 때문이다(link prefetch). 크롤러·백그라운드
     * 탭 열기·메일 클라이언트의 링크 검사도 같은 일을 한다. <b>상태를 바꾸는 요청을 GET 으로
     * 두면 사용자가 보지도 않은 알림이 읽음이 된다.</b> POST 는 그렇게 당겨지지 않는다.
     *
     * <p>목록을 여는 것만으로 전부 읽음 처리하지 않는 이유는, 안 읽은 알림이 여러 건일 때
     * 사용자가 실제로 확인한 것만 남기려는 것이다 — 목록을 스쳐 지나갔다고 다 읽은 게 아니다.
     *
     * <p>남의 알림 id 를 넣으면 아무 일도 안 일어나고 목록으로 되돌아온다 — 없는 id 와
     * 같은 응답이라, 그 알림이 존재하는지가 응답 차이로 새어 나가지 않는다.
     */
    @PostMapping("/mypage/notifications/{id}/read")
    public String readNotification(@PathVariable Long id,
                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return notificationService.markRead(id, principal.getMemberId())
                .map(this::redirectAfterRead)
                .orElse("redirect:/mypage/notifications");
    }

    /**
     * 회차를 아는 알림(취소표 알림)은 좌석도로 바로 보낸다 — 사용자가 기다리던 건 "그 회차"의
     * 빈자리라서, 공연 상세에 내려주고 다시 찾게 하면 한 걸음 낭비다. 그 외에는 공연 상세로
     * 보내되, 관람평 요청 알림은 관람평 칸까지 데려간다 — 이 알림을 누른 이유가 그거니까.
     */
    private String redirectAfterRead(NotificationService.ReadResult result) {
        if (result.scheduleId() != null) {
            return "redirect:/schedules/" + result.scheduleId() + "/seats";
        }
        String base = "redirect:/performances/" + result.performanceId();
        return result.type() == NotificationType.REVIEW_REQUESTED ? base + "#reviews" : base;
    }

    /** 상단 탭의 안 읽은 알림 배지. 세 화면이 같은 탭을 공유하므로 어디서 열어도 같은 수가 보여야 한다. */
    private void addUnreadCount(CustomUserDetails principal, Model model) {
        model.addAttribute("unreadCount", notificationService.countUnread(principal.getMemberId()));
    }
}
