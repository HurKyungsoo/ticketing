package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

    @PostMapping("/signup")
    public String signup(@RequestParam String loginId,
                          @RequestParam String password,
                          @RequestParam String nickname,
                          Model model) {
        try {
            memberService.signup(loginId, password, nickname);
        } catch (IllegalStateException e) {
            model.addAttribute("error", e.getMessage());
            return "member/signup";
        }
        return "redirect:/login";
    }
}
