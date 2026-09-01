package com.portfolio.ticket.controller;

import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 안 읽은 알림 수를 모든 페이지 뷰에 넣어준다. 헤더(fragment/header.html)의
 * 「마이페이지」 옆 배지가 쓴다.
 *
 * <p><b>왜 필요한가.</b> 이 값은 원래 마이페이지 네 화면(MyPageController)에만 있어서,
 * 마이페이지 탭 배지로만 보였다 — 알림이 와도 마이페이지를 직접 열어보기 전엔 알 방법이
 * 없었다. 헤더는 거의 모든 화면이 공유하는 조각이라, {@link CurrentPathAdvice} 와 같은
 * 방식(전역 어드바이스)으로 여기서 한 번만 계산해 물려준다.
 *
 * <p><b>{@code @Controller} 로만 범위를 좁힌 이유.</b> {@code annotations = Controller.class}
 * 라 {@code @RestController}(ReservationApiController 등 JSON API)에는 안 붙는다 — 그
 * 응답은 화면을 그리지 않으므로 헤더도 없고, 안 붙이면 API 호출마다 이 카운트 쿼리가
 * 쓸데없이 한 번씩 더 나간다.
 *
 * <p>PageExceptionHandler(403/404/5xx) 는 {@code @Controller} 가 아니라 이 어드바이스가
 * 안 붙는다 — 그 화면들은 헤더에서 {@code unreadCount} 가 null 인 채로 오고, 헤더는
 * null 을 배지 미표시로 처리한다.
 */
@ControllerAdvice(annotations = Controller.class)
@RequiredArgsConstructor
public class UnreadNotificationCountAdvice {

    private final NotificationService notificationService;

    @ModelAttribute("unreadCount")
    public long unreadCount(@AuthenticationPrincipal CustomUserDetails principal) {
        return principal == null ? 0L : notificationService.countUnread(principal.getMemberId());
    }
}
