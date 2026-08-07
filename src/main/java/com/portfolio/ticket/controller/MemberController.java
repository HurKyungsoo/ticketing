package com.portfolio.ticket.controller;

import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

@Controller
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    /**
     * 로그인 화면. <b>이미 로그인한 사람에게는 안 보여준다.</b>
     *
     * <p>마이페이지에서 뒤로가기를 누르면 여기로 온다 — 로그인하느라 거쳐온 주소가 히스토리에
     * 남아 있기 때문이다. 그때 로그인 폼이 다시 뜨면 "로그인이 풀렸나" 싶어진다.
     *
     * <p>보내는 곳은 {@code returnTo} 가 있으면 그쪽이다. 헤더의 「로그인」으로 들어온
     * 경우 그 값이 <b>로그인하기 직전에 보던 화면</b>이라, 뒤로가기의 원래 의미와 맞는다.
     * 없으면 홈으로 보낸다.
     */
    @GetMapping("/login")
    public String loginForm(@AuthenticationPrincipal CustomUserDetails principal,
                             @RequestParam(required = false) String returnTo) {
        return principal == null ? "member/login" : "redirect:" + safeOrHome(returnTo);
    }

    /** 회원가입도 같다 — 이미 계정이 있는 사람에게 가입 폼을 보여줄 이유가 없다. */
    @GetMapping("/signup")
    public String signupForm(@AuthenticationPrincipal CustomUserDetails principal,
                              @RequestParam(required = false) String returnTo) {
        return principal == null ? "member/signup" : "redirect:" + safeOrHome(returnTo);
    }

    /**
     * 사이트 내부 경로만 허용한다 — 오픈 리다이렉트 방지.
     * ({@code PostLoginRedirectHandler.isSafe} 와 같은 규칙이다. 그쪽은 로그인 성공 직후,
     * 이쪽은 이미 로그인한 사람이 로그인 화면에 닿았을 때라 시점이 다르다.)
     */
    private String safeOrHome(String returnTo) {
        boolean safe = returnTo != null && returnTo.startsWith("/")
                && !returnTo.startsWith("//") && !returnTo.contains("://");
        return safe ? returnTo : "/";
    }

    /**
     * @param returnTo 가입 전에 보던 화면. 가입 직후 로그인 화면으로 넘길 때 그대로 들려 보내,
     *                 로그인까지 마치면 원래 자리로 돌아가게 한다. 여기서 검증하지 않는 이유는
     *                 실제로 이동하는 주체가 {@code PostLoginRedirectHandler} 이고, 그쪽이
     *                 사이트 내부 경로인지 확인하기 때문이다(오픈 리다이렉트 방지).
     */
    @PostMapping("/signup")
    public String signup(@RequestParam String loginId,
                          @RequestParam String password,
                          @RequestParam String nickname,
                          @RequestParam String email,
                          @RequestParam(required = false) String returnTo,
                          Model model) {
        try {
            memberService.signup(loginId, password, nickname, email);
        } catch (IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            // 실패해서 폼을 다시 그릴 때도 들고 있어야 한다 — 여기서 흘리면 다시 제출한
            // 사용자만 조용히 홈으로 떨어진다.
            model.addAttribute("returnTo", returnTo);
            return "member/signup";
        }
        return returnTo == null || returnTo.isBlank()
                ? "redirect:/login"
                : "redirect:/login?returnTo=" + UriUtils.encodeQueryParam(returnTo, StandardCharsets.UTF_8);
    }
}
