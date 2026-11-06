package com.portfolio.ticket.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;

/**
 * 비로그인 사용자가 인증이 필요한 곳에 들어오면 로그인으로 보내되, 없는 주소면 404 를 준다.
 *
 * 없는 주소까지 로그인으로 보내면 로그인 뒤에야 404 를 보게 되고, 크롤러에게는 멀쩡한 페이지처럼 보인다.
 * 저장된 요청도 지운다. 남겨 두면 404 화면에서 로그인했을 때 그 없는 주소로 되돌아간다.
 */
@Component
public class NotFoundAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final RequestMappingHandlerMapping handlerMapping;
    private final RequestCache requestCache;
    private final AuthenticationEntryPoint loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");

    public NotFoundAwareAuthenticationEntryPoint(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            RequestCache requestCache) {
        this.handlerMapping = handlerMapping;
        this.requestCache = requestCache;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (hasHandler(request)) {
            loginEntryPoint.commence(request, response, authException);
            return;
        }
        requestCache.removeRequest(request, response);
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /** 주소는 있는데 HTTP 메서드만 다른 경우 등 판단 중 예외가 나면, 기존처럼 로그인으로 보낸다. */
    private boolean hasHandler(HttpServletRequest request) {
        ServletRequestPathUtils.parseAndCache(request);
        try {
            return handlerMapping.getHandler(request) != null;
        } catch (Exception e) {
            return true;
        } finally {
            ServletRequestPathUtils.clearParsedRequestPath(request);
        }
    }
}
