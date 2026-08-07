package com.portfolio.ticket.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 로그인 성공 후 원래 하려던 화면으로 돌려보낸다. 세 가지 경로가 있고 순서가 중요하다.
 *
 * <ol>
 *   <li><b>{@code returnTo} 파라미터</b> — 화면이 "여기로 돌아와 달라"고 명시한 경우.
 *       좌석도(선점 이어가기)·찜처럼 <b>로그인 없이도 볼 수 있는</b> 화면에서 어떤 동작을
 *       하려다 막힌 흐름이다. 폼 로그인은 hidden input 으로 그대로 POST 되어 온다.
 *   <li><b>세션에 저장해둔 값</b> — 위와 같은 뜻이지만 카카오/네이버는 외부 사이트를
 *       거쳐 돌아오면서 쿼리파라미터가 유실되므로 {@link OAuth2ReturnToCaptureFilter} 가
 *       미리 넣어둔 것을 쓴다.
 *   <li><b>스프링이 저장한 원래 요청(saved request)</b> — 인증이 필요한 주소로 바로 들어와
 *       로그인으로 튕긴 경우. 헤더의 「마이페이지」가 여기 해당한다.
 * </ol>
 *
 * <p><b>3번이 없어서 헤더의 마이페이지가 로그인 후 홈으로 떨어졌다.</b> 그 링크는
 * {@code returnTo} 를 붙이지 않는다 — 붙일 필요가 없어야 맞다. 인증이 필요한 주소로
 * 갔다가 막힌 것은 스프링이 이미 기록해 두므로(ExceptionTranslationFilter →
 * RequestCache) 그걸 읽으면 된다. 링크마다 returnTo 를 손으로 붙이는 방식은 하나만
 * 빠뜨려도 조용히 홈으로 떨어지고, 그게 실제로 일어난 일이다.
 */
@Component
@RequiredArgsConstructor
public class PostLoginRedirectHandler implements AuthenticationSuccessHandler {

    public static final String SESSION_KEY = "POST_LOGIN_REDIRECT";

    /** 저장하는 쪽과 같은 인스턴스여야 한다 — 이유는 SecurityConfig.requestCache() 주석 참고. */
    private final RequestCache requestCache;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        String explicit = explicitTarget(request);
        if (explicit != null) {
            // 명시적 목적지가 이겼으므로 저장된 요청은 버린다 — 안 지우면 세션에 남아
            // 다음 로그인 때 엉뚱한 화면으로 데려간다.
            requestCache.removeRequest(request, response);
            response.sendRedirect(request.getContextPath() + explicit);
            return;
        }

        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            requestCache.removeRequest(request, response);
            if (isResumable(saved)) {
                // getRedirectUrl() 은 컨텍스트 경로까지 포함한 절대주소다 — 여기에
                // contextPath 를 또 붙이면 경로가 두 번 들어간다.
                response.sendRedirect(saved.getRedirectUrl());
                return;
            }
        }

        response.sendRedirect(request.getContextPath() + "/");
    }

    /** 화면이 직접 지정한 목적지({@code returnTo} 또는 그 세션 사본). 없으면 null. */
    private String explicitTarget(HttpServletRequest request) {
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
        return null;
    }

    /**
     * 저장된 요청으로 되돌아가도 되는지.
     *
     * <p><b>GET 만 허용한다.</b> 세션이 끊긴 상태에서 찜 토글 같은 POST 나 fetch 가
     * 로그인으로 튕기면 그 주소가 저장되는데, 그대로 보내면 사용자가 JSON 응답을
     * 마주하거나 GET 으로 바뀐 요청이 404 가 된다.
     *
     * <p>로그인 화면 자신도 제외한다 — 로그인하자마자 다시 로그인 화면이 뜨면
     * 실패한 것처럼 보인다.
     */
    private boolean isResumable(SavedRequest saved) {
        if (!"GET".equalsIgnoreCase(saved.getMethod())) {
            return false;
        }
        String url = saved.getRedirectUrl();
        return url != null && !url.contains("/login") && !url.contains("/signup");
    }

    /** "/" 로 시작하는 사이트 내부 상대경로만 허용한다 — 오픈 리다이렉트 방지. */
    private boolean isSafe(String value) {
        return value != null && value.startsWith("/") && !value.startsWith("//") && !value.contains("://");
    }
}
