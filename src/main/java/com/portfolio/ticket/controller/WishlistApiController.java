package com.portfolio.ticket.controller;

import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.WishlistService;
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
public class WishlistApiController {

    private final WishlistService wishlistService;

    /**
     * 찜 토글. 응답의 {@code wishlisted} 는 <b>호출 후의 상태</b>라, 화면은 이 값만 반영하면
     * 자기가 뭘 눌렀는지 따로 셈하지 않아도 된다(따닥 눌러 요청 순서가 뒤집혀도 서버 상태를 따른다).
     *
     * <p>이 경로는 {@code permitAll} 목록에 없어 미인증이면 폼 로그인으로 302 된다.
     * 화면 스크립트가 그 리다이렉트를 보고 로그인으로 보낸다(좌석 선점과 같은 방식).
     */
    @PostMapping("/performances/{performanceId}/wishlist")
    public ResponseEntity<?> toggle(@PathVariable Long performanceId,
                                     @AuthenticationPrincipal CustomUserDetails principal) {
        boolean wishlisted = wishlistService.toggle(principal.getMemberId(), performanceId);
        return ResponseEntity.ok(Map.of("wishlisted", wishlisted));
    }
}
