package com.portfolio.ticket.service;

/** 한 회원이 저장할 수 있는 검색 조건 수(SavedSearchService.MAX_SAVED_SEARCHES_PER_MEMBER)를 넘었다. */
public class TooManySavedSearchesException extends RuntimeException {
    public TooManySavedSearchesException(String message) {
        super(message);
    }
}
