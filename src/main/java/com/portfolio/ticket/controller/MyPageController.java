package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.Wishlist;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.WishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class MyPageController {

    private final ReservationService reservationService;
    private final WishlistService wishlistService;

    @GetMapping("/mypage/reservations")
    public String reservations(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        List<Reservation> reservations = reservationService.findMyReservations(principal.getMemberId());
        model.addAttribute("reservations", reservations);
        // 결제 가능 여부(선점 만료) 판단을 템플릿에서 하기 위해 기준 시각을 그대로 넘긴다.
        model.addAttribute("now", LocalDateTime.now());
        return "member/reservations";
    }

    @GetMapping("/mypage/wishlist")
    public String wishlist(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        List<Wishlist> wishes = wishlistService.findMyWishlist(principal.getMemberId());
        model.addAttribute("wishes", wishes);
        return "member/wishlist";
    }
}
