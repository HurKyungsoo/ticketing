package com.portfolio.ticket.external;

/**
 * KOPIS 에서 수집을 이어갈 수 없는 실패. 받을 게 없는 빈 응답과 구분하려고 쓴다.
 * 이 예외로 끝난 수집은 실패로 기록되어 증분 수집 기준일을 옮기지 않는다.
 */
public class KopisFetchException extends RuntimeException {

    public KopisFetchException(String message) {
        super(message);
    }

    public KopisFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
