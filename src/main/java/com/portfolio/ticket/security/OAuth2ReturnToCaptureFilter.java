package com.portfolio.ticket.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * "/oauth2/authorization/{registrationId}?returnTo=..." 요청이 카카오/네이버로 리다이렉트되기 직전에
 * returnTo 를 세션에 저장해둔다. 카카오/네이버를 거쳐 돌아오는 콜백 요청에는 이 쿼리파라미터가
 * 없기 때문에, PostLoginRedirectHandler 가 세션에서 다시 꺼내 쓴다.
 */
@Component
public class OAuth2ReturnToCaptureFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String returnTo = request.getParameter("returnTo");
        if (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains("://")) {
            request.getSession().setAttribute(PostLoginRedirectHandler.SESSION_KEY, returnTo);
        }
        chain.doFilter(request, response);
    }
}
