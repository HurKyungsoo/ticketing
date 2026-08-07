package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.MemberService;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/login")
    public String loginForm() {
        return "member/login";
    }

    @GetMapping("/signup")
    public String signupForm() {
        return "member/signup";
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
