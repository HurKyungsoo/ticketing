package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.ForbiddenException;
import com.portfolio.ticket.service.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * {@code @RestController} 가 던진 예외를 JSON 으로 변환한다.
 *
 * 화면용 {@link PageExceptionHandler} 와 나눈 이유: 같은 예외라도 API 는 JSON 을,
 * 화면은 HTML 을 받아야 한다. Accept 헤더를 보고 분기하는 방법도 있지만 fetch 요청이
 * 헤더를 항상 제대로 붙여주진 않으므로, 컨트롤러 종류로 나누는 쪽이 확실하다.
 *
 * {@code @Order(1)} 이라 {@code @RestController} 는 항상 이 어드바이스를 먼저 만난다.
 * 맨 아래 catch-all 이 있어서 화면용 어드바이스로 넘어가는 일은 없다.
 * 단, 컨트롤러 안에 직접 선언된 {@code @ExceptionHandler} 가 어드바이스보다 우선하므로
 * {@link ReservationApiController} 의 좌석 선점 응답(409 등)은 그대로 유지된다.
 */
@Slf4j
@Order(1)
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<?> handleForbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 타입이 안 맞는 경로변수({@code /api/seats/abc/hold}). 클라이언트 잘못이므로 400 이고
     * 스택은 남기지 않는다 — 봇이 긁고 가면 로그가 스택으로 뒤덮인다.
     *
     * 아래 {@code ErrorResponse} 핸들러로 묶이지 않는 이유: 이 예외만
     * {@code TypeMismatchException} 을 상속할 뿐 {@link ErrorResponse} 를 구현하지 않는다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                 HttpServletRequest request) {
        log.debug("경로변수 타입 불일치. uri={}, name={}", request.getRequestURI(), e.getName());
        return body(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
    }

    /**
     * 위에서 걸리지 않은 나머지 전부. 예외 메시지를 그대로 내보내지 않는다 —
     * SQL 문이나 내부 경로가 섞여 나올 수 있어서, 클라이언트에는 고정 문구만 주고
     * 원인은 서버 로그에 스택으로 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception e, HttpServletRequest request) throws Exception {
        rethrowSecurityException(e);

        HttpStatusCode mvcStatus = clientErrorStatusOf(e);
        if (mvcStatus != null) {
            log.debug("잘못된 요청. uri={}, status={}, ex={}", request.getRequestURI(), mvcStatus.value(), e);
            String message = mvcStatus.value() == HttpStatus.NOT_FOUND.value()
                    ? "요청하신 리소스를 찾을 수 없습니다."
                    : "요청 형식이 올바르지 않습니다.";
            return ResponseEntity.status(mvcStatus).body(Map.of("message", message));
        }

        log.error("처리되지 않은 예외. uri={}", request.getRequestURI(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 스프링 MVC 표준 예외(없는 경로 404, 허용되지 않은 메서드 405, 검증 실패 400 …)면
     * 그 예외가 스스로 아는 4xx 상태코드를, 아니면 null 을 준다.
     *
     * 이걸 걸러내지 않으면 위 catch-all 이 없는 주소 요청까지 삼켜서 500 이 나가고
     * 로그에 스택이 쌓인다. {@code @ExceptionHandler(ErrorResponse.class)} 로 따로 빼고
     * 싶지만 {@link ErrorResponse} 는 Throwable 이 아니라 인터페이스라 걸리지 않는다
     * (핸들러 파라미터는 {@code Class<? extends Throwable>} 이어야 한다).
     * 5xx 를 뜻하는 ErrorResponse 는 여기서 걸러내지 않고 아래 500 처리로 흘려보낸다.
     */
    private HttpStatusCode clientErrorStatusOf(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return response.getStatusCode();
        }
        return null;
    }

    /**
     * 스프링 시큐리티 예외는 어드바이스가 삼키면 안 된다. 이 예외들은 컨트롤러보다
     * 바깥에 있는 {@code ExceptionTranslationFilter} 가 받아서 로그인 리다이렉트나
     * 403 응답으로 바꾸는데, 여기서 500 JSON 으로 먹어버리면 그 처리가 통째로 사라진다.
     */
    private void rethrowSecurityException(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
    }

    private ResponseEntity<?> body(HttpStatus status, String message) {
        String resolved = (message == null || message.isBlank()) ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(Map.of("message", resolved));
    }
}
