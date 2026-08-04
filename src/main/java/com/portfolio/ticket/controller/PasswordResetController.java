package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @GetMapping("/password-reset")
    public String requestForm(Model model) {
        model.addAttribute("sent", false);
        return "member/password-reset-request";
    }

    /**
     * 계정 존재 여부와 무관하게 항상 같은 "발송했습니다" 화면을 보여준다 — 아이디/이메일이
     * 안 맞는지 여부를 응답으로 구분할 수 있으면 계정 존재 여부를 캐낼 수 있다.
     */
    @PostMapping("/password-reset")
    public String requestReset(@RequestParam String loginId, @RequestParam String email, Model model) {
        String resetUrlPrefix = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/password-reset/")
                .build()
                .toUriString();
        passwordResetService.requestReset(loginId, email, resetUrlPrefix);
        model.addAttribute("sent", true);
        return "member/password-reset-request";
    }

    @GetMapping("/password-reset/{token}")
    public String resetForm(@PathVariable String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("valid", passwordResetService.isValidToken(token));
        return "member/password-reset-form";
    }

    @PostMapping("/password-reset/{token}")
    public String reset(@PathVariable String token,
                         @RequestParam String password,
                         @RequestParam String passwordConfirm,
                         Model model) {
        if (password.length() < 8) {
            return invalidInput(token, model, "비밀번호는 8자 이상이어야 합니다.");
        }
        if (!password.equals(passwordConfirm)) {
            return invalidInput(token, model, "비밀번호가 일치하지 않습니다.");
        }

        try {
            passwordResetService.resetPassword(token, password);
        } catch (NotFoundException e) {
            model.addAttribute("valid", false);
            return "member/password-reset-form";
        }
        return "redirect:/login?resetDone";
    }

    private String invalidInput(String token, Model model, String message) {
        model.addAttribute("token", token);
        model.addAttribute("valid", true);
        model.addAttribute("error", message);
        return "member/password-reset-form";
    }
}
