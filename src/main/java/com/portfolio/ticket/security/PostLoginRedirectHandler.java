package com.portfolio.ticket.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 성공 후 원래 하려던 화면(예: 좌석 선택)으로 돌아가기 위한 핸들러.
 * 폼 로그인은 returnTo 가 로그인 폼의 hidden input 으로 그대로 POST 되어 오지만,
 * 카카오/네이버 로그인은 외부 사이트를 거쳐 돌아오면서 원래 요청의 쿼리파라미터가 유실되므로
 * {@link OAuth2ReturnToCaptureFilter} 가 미리 세션에 저장해둔 값을 대신 사용한다.
 */
@Component
public class PostLoginRedirectHandler implements AuthenticationSuccessHandler {

    public static final String SESSION_KEY = "POST_LOGIN_REDIRECT";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        response.sendRedirect(request.getContextPath() + resolveTarget(request));
    }

    private String resolveTarget(HttpServletRequest request) {
        String returnTo = request.getParameter("returnTo");
        if (isSafe(returnTo)) {
            return returnTo;
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            Object stashed = session.getAttribute(SESSION_KEY);
            session.removeAttribute(SESSION_KEY);
            if (stashed instanceof String s && isSafe(s)) {
                return s;
            }
        }
        return "/";
    }

    /** "/" 로 시작하는 사이트 내부 상대경로만 허용한다 — 오픈 리다이렉트 방지. */
    private boolean isSafe(String value) {
        return value != null && value.startsWith("/") && !value.startsWith("//") && !value.contains("://");
    }
}
