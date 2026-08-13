package com.portfolio.ticket.controller;

import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.RestockAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RestockAlertApiController {

    private final RestockAlertService restockAlertService;

    /**
     * 취소표 알림 구독 토글. {@link WishlistApiController#toggle} 과 같은 방식이다 — 응답의
     * {@code subscribed} 는 호출 후의 상태이고, 이 경로는 {@code permitAll} 목록에 없어
     * 미인증이면 폼 로그인으로 302 된다(화면 스크립트가 그 리다이렉트를 보고 로그인으로 보낸다).
     */
    @PostMapping("/schedules/{scheduleId}/restock-alert")
    public ResponseEntity<?> toggle(@PathVariable Long scheduleId,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        boolean subscribed = restockAlertService.toggle(principal.getMemberId(), scheduleId);
        return ResponseEntity.ok(Map.of("subscribed", subscribed));
    }
}
