package com.portfolio.ticket.controller;

import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 관람평 작성·삭제.
 *
 * <p>둘 다 <b>POST 다.</b> 상태를 바꾸는 요청을 GET 으로 두면 크롬의 링크 미리 가져오기나
 * 크롤러가 대신 눌러버린다 — 알림 읽음 처리에서 실제로 겪었다(PostLoginRedirectHandler 근처
 * 주석과 MyPageController.readNotification 참고).
 *
 * <p>끝나면 공연 상세로 되돌린다(PRG). 새로고침으로 같은 관람평이 다시 제출되지 않게 하고,
 * 사용자는 자기가 쓴 글이 목록에 붙은 걸 그 자리에서 본다.
 */
@Controller
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/performances/{performanceId}/reviews")
    public String write(@PathVariable Long performanceId,
                         @RequestParam int rating,
                         @RequestParam String content,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes redirect) {
        try {
            reviewService.write(principal.getMemberId(), principal.getNickname(), performanceId, rating, content);
        } catch (IllegalArgumentException e) {
            // 값이 잘못된 건 사용자가 고칠 수 있는 문제라 화면에 그대로 알린다.
            // 자격 없음(ForbiddenException)은 여기서 안 잡는다 — 화면이 폼을 아예 안 그리므로
            // 여기까지 왔다면 폼을 우회한 요청이고, 403 페이지로 나가는 게 맞다.
            redirect.addFlashAttribute("reviewError", e.getMessage());
        }
        return "redirect:/performances/" + performanceId + "#reviews";
    }

    @PostMapping("/performances/{performanceId}/reviews/{reviewId}/delete")
    public String delete(@PathVariable Long performanceId,
                          @PathVariable Long reviewId,
                          @AuthenticationPrincipal CustomUserDetails principal) {
        reviewService.delete(reviewId, principal.getMemberId());
        return "redirect:/performances/" + performanceId + "#reviews";
    }
}
