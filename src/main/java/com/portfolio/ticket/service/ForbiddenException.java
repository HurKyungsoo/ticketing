package com.portfolio.ticket.service;

/**
 * 로그인은 했지만 그 리소스에 대한 권한이 없을 때(주로 남의 예매). HTTP 403 으로 매핑된다.
 *
 * 인증이 없는 401 과는 다르다 — 로그인 화면으로 보내봐야 소용없고, 다시 로그인해도
 * 결과가 같다. 종전에는 {@link IllegalStateException} 이라 화면에서는 500,
 * API 에서는 400 이 나갔다. 둘 다 "권한 없음" 을 뜻하지 않는다.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
