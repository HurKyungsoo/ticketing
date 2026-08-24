package com.portfolio.ticket.service;

/**
 * 결제가 승인되어 예매가 확정됐다.
 *
 * <p>{@link SeatsReleasedEvent} 와 같은 이유로 발행 지점(트랜잭션 안)과 처리 지점(커밋 후)을
 * 나누는데, 여기서는 그 이유가 하나 더 있다. 발행 지점인
 * {@code ReservationService.confirmPayment()} 는 <b>좌석 행을 {@code FOR UPDATE} 로 잠근 채</b>
 * 도는 트랜잭션이다. 그 안에서 SMTP 를 붙잡으면 메일 서버가 느린 만큼 좌석 락이 길어지고,
 * 오픈런 때 같은 좌석을 기다리는 요청이 전부 같이 늘어진다 — 락 타임아웃을 3초로 둔 이유와
 * 정확히 같은 종류의 문제를 메일이 만드는 셈이다.
 *
 * <p>롤백 위험도 있다. 메일 발송 실패가 이 트랜잭션을 롤백시키면 <b>토스 승인은 끝났는데
 * 예매는 없는</b> 상태가 된다 — 돈만 나간 상태다. 커밋된 뒤에만 처리하면 둘 다 없다.
 */
public record ReservationConfirmedEvent(Long reservationId) {}
