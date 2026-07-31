package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.ForbiddenException;
import com.portfolio.ticket.service.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Thymeleaf 화면을 돌려주는 컨트롤러의 예외를 사람이 읽을 수 있는 에러 페이지로 바꾼다.
 *
 * 종전에는 이 어드바이스가 없어서 없는 공연 주소나 남의 예매 결제 페이지가 전부
 * Whitelabel Error Page(500)로 나갔다. 상태코드도 틀리고(권한 없음이 500), 헤더도
 * 없어서 사용자가 돌아갈 길이 없는 흰 화면이었다.
 *
 * {@code @Order(2)} 로 {@link ApiExceptionHandler}(1) 뒤에 둔다. 이 어드바이스는
 * 셀렉터가 없어 {@code @RestController} 에도 붙지만, API 어드바이스에 catch-all 이
 * 있어서 실제로는 항상 그쪽에서 끝난다.
 */
@Slf4j
@Order(2)
@ControllerAdvice
public class PageExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView handleNotFound(NotFoundException e) {
        return page(HttpStatus.NOT_FOUND, "error/404", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ModelAndView handleForbidden(ForbiddenException e) {
        return page(HttpStatus.FORBIDDEN, "error/403", e.getMessage());
    }

    /**
     * {@code /performances/abc} 처럼 경로변수 타입이 안 맞는 경우. 화면에서는 400 보다
     * 404 가 맞다 — 사용자에게는 "그런 공연 페이지는 없다" 와 같은 상황이고,
     * 400 전용 페이지를 따로 두면 설명할 게 없는 화면이 하나 더 생긴다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                            HttpServletRequest request) {
        log.debug("경로변수 타입 불일치. uri={}, name={}", request.getRequestURI(), e.getName());
        return page(HttpStatus.NOT_FOUND, "error/404", null);
    }

    /**
     * 나머지 전부. 예외 메시지는 화면에 내보내지 않는다 — 내부 구조가 드러날 수 있고,
     * 사용자가 할 수 있는 일도 없다. 원인은 스택으로 로그에만 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleUnexpected(Exception e, HttpServletRequest request) throws Exception {
        rethrowSecurityException(e);

        HttpStatusCode mvcStatus = clientErrorStatusOf(e);
        if (mvcStatus != null) {
            log.debug("MVC 4xx. uri={}, status={}", request.getRequestURI(), mvcStatus.value());
            // 4xx 는 403 만 따로 두고 나머지는 404 페이지로 모은다 — 브라우저로 들어온
            // 사용자에게 405 나 415 는 "그 주소로는 볼 게 없다" 와 구별되지 않고,
            // 설명할 것도 없는 화면을 상태코드마다 하나씩 만들 이유가 없다.
            return mvcStatus.value() == HttpStatus.FORBIDDEN.value()
                    ? page(HttpStatus.FORBIDDEN, "error/403", null)
                    : page(HttpStatus.NOT_FOUND, "error/404", null);
        }

        log.error("처리되지 않은 예외. uri={}", request.getRequestURI(), e);
        return page(HttpStatus.INTERNAL_SERVER_ERROR, "error/5xx", null);
    }

    /**
     * 스프링 MVC 표준 예외(없는 경로 404, 허용되지 않은 메서드 405 …)면 그 예외가 스스로
     * 아는 4xx 상태코드를, 아니면 null 을 준다.
     *
     * 이걸 걸러내지 않으면 위 catch-all 이 <b>오타 난 주소까지 삼켜서 500 페이지로 내보내고</b>
     * 로그에 스택을 쌓는다. {@code @ExceptionHandler(ErrorResponse.class)} 로 빼고 싶지만
     * {@link ErrorResponse} 는 Throwable 이 아니라 인터페이스라 걸리지 않는다
     * (핸들러 파라미터는 {@code Class<? extends Throwable>} 이어야 한다).
     */
    private HttpStatusCode clientErrorStatusOf(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return response.getStatusCode();
        }
        return null;
    }

    /**
     * 시큐리티 예외는 어드바이스가 삼키면 안 된다 — 컨트롤러 바깥의
     * {@code ExceptionTranslationFilter} 가 로그인 리다이렉트/403 으로 바꿔야 하는데,
     * 여기서 5xx 페이지로 먹어버리면 그 처리가 사라진다.
     */
    private void rethrowSecurityException(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
    }

    /** {@code reason} 은 사용자에게 보여도 되는 문구일 때만 넘긴다(없으면 템플릿 기본 문구). */
    private ModelAndView page(HttpStatus status, String view, String reason) {
        ModelAndView mav = new ModelAndView(view);
        mav.setStatus(status);
        if (reason != null && !reason.isBlank()) {
            mav.addObject("reason", reason);
        }
        return mav;
    }
}
