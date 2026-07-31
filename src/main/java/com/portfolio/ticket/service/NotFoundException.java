package com.portfolio.ticket.service;

/**
 * 요청한 리소스가 없을 때. HTTP 404 로 매핑된다.
 *
 * 종전에는 이런 경우에도 {@link IllegalArgumentException} 을 던졌는데, 그러면
 * "없는 공연 id" 와 "잘못된 파라미터" 를 상태코드로 구분할 수 없다. 화면 쪽에는
 * 핸들러가 아예 없어서 없는 공연 주소를 열면 Whitelabel 500 이 나갔다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
