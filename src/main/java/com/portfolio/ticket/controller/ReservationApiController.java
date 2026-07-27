package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.external.PerformanceSyncScheduler;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.ReservationFacade;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.SeatAlreadyTakenException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReservationApiController {

    private final ReservationFacade reservationFacade;
    private final ReservationService reservationService;
    private final PerformanceSyncScheduler syncScheduler;

    /** 좌석 선점. strategy 파라미터로 동시성 전략을 바꿔가며 측정할 수 있다. */
    @PostMapping("/seats/{seatId}/hold")
    public ResponseEntity<?> hold(@PathVariable Long seatId,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  @RequestParam(defaultValue = "PESSIMISTIC") HoldStrategy strategy) {
        Reservation reservation = reservationFacade.hold(strategy, seatId, principal.getMemberId());
        return ResponseEntity.ok(Map.of(
                "reservationNo", reservation.getReservationNo(),
                "amount", reservation.getAmount(),
                "holdExpiresAt", reservation.getHoldExpiresAt()
        ));
    }

    @PostMapping("/reservations/{reservationNo}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String reservationNo,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        int refund = reservationService.cancel(reservationNo, principal.getMemberId());
        return ResponseEntity.ok(Map.of("refundAmount", refund));
    }

    /** 배치를 기다리지 않고 수동으로 공공데이터를 당겨올 때. 소스별 시도/수신/파싱성공/
     *  isValid 탈락/신규저장 건수를 그대로 내려줘서 "호출 실패"와 "중복이라 신규 0건"을 구분할 수 있다. */
    @PostMapping("/admin/sync")
    public ResponseEntity<?> sync() {
        return ResponseEntity.ok(syncScheduler.runSync());
    }

    @ExceptionHandler(SeatAlreadyTakenException.class)
    public ResponseEntity<?> handleTaken(SeatAlreadyTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<?> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
