package com.portfolio.ticket.mapper.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 좌석 하나의 현재 상태. 좌석도 실시간 갱신 전용이다.
 *
 * <p>{@link SeatMapRow} 를 재사용하지 않는 이유: 저건 좌석도를 처음 그릴 때 쓰는 것이라
 * 구역·줄·번호·통로·등급·가격까지 다 들고 있다. 갱신은 몇 초마다 반복되는 요청이라
 * 바뀔 수 있는 값(상태) 하나만 나른다 — 좌석 3,000개짜리 홀에서 이 차이가 그대로
 * 응답 크기가 된다. 나머지 값은 이미 화면에 그려져 있어 다시 보낼 이유가 없다.
 */
@Getter
@Setter
public class SeatStatusRow {
    private Long seatId;
    private String status;
}
