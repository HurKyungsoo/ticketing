package com.portfolio.ticket.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Set;

/**
 * 지금 보고 있는 화면의 경로를 모든 뷰에 넣어준다. 헤더의 「로그인」이 "여기로 돌아와 달라"
 * ({@code returnTo})를 붙이는 데 쓴다.
 *
 * <p><b>왜 필요한가.</b> 로그인이 필요해서 <b>막힌</b> 화면은 스프링이 기록해 두므로
 * {@code PostLoginRedirectHandler} 가 알아서 되돌려 보낸다. 하지만 공연 상세처럼
 * <b>로그인 없이도 볼 수 있는</b> 화면에서 사용자가 스스로 「로그인」을 누른 경우엔
 * 막힌 요청이 없다 — 서버 입장에서는 그냥 로그인 화면을 연 것이라 보던 자리를 알 방법이 없다.
 * 그래서 링크가 직접 실어 보낸다.
 *
 * <p><b>Thymeleaf 에서 직접 못 구하는 이유.</b> 3.1 부터 템플릿에서 {@code #request} 에
 * 접근할 수 없다(SEO 메타에서 baseUrl 을 컨트롤러가 넘기는 것과 같은 사정이다).
 */
@ControllerAdvice
public class CurrentPathAdvice {

    /**
     * 돌아갈 자리로 삼으면 안 되는 경로. 로그인 화면에서 로그인하면 다시 로그인 화면으로
     * 돌아오고, 비밀번호 재설정 중에 로그인하면 재설정 폼으로 되돌아간다 — 둘 다 막다른 길이다.
     */
    private static final Set<String> EXCLUDED = Set.of(
            "/login", "/signup",
            // 홈은 로그인 후 기본 도착지라 실어 보낼 이유가 없다. 붙이면 주소만 길어진다.
            "/");

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        // GET 으로 연 화면만 대상이다. POST 결과 화면으로 되돌아가면 같은 요청을 다시
        // 보낼 수 없어 빈 화면이 되거나 405 가 난다.
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.isEmpty() || EXCLUDED.contains(path) || path.startsWith("/password-reset")) {
            return null;
        }

        String query = request.getQueryString();
        // 목록의 필터·페이지처럼 조건이 붙은 화면은 그 조건까지 있어야 "보던 자리"가 된다.
        return (query == null || query.isBlank()) ? path : path + "?" + query;
    }
}
