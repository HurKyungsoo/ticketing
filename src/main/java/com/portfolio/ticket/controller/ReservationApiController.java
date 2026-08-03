package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.external.PerformanceSyncScheduler;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.HoldStrategy;
import com.portfolio.ticket.service.PartialSeatHoldException;
import com.portfolio.ticket.service.ReservationFacade;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.SeatRegenerationService;
import com.portfolio.ticket.service.SeatAlreadyTakenException;
import com.portfolio.ticket.service.PurchaseLimitExceededException;
import com.portfolio.ticket.service.TooManyHoldAttemptsException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReservationApiController {

    private final ReservationFacade reservationFacade;
    private final ReservationService reservationService;
    private final PerformanceSyncScheduler syncScheduler;
    private final SeatRegenerationService seatRegenerationService;

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

    /** 좌석 여러 개를 한 번에 선점해 예매번호 하나로 묶는다. */
    @PostMapping("/reservations/hold")
    public ResponseEntity<?> holdMultiple(@RequestBody MultiSeatHoldRequest request,
                                           @AuthenticationPrincipal CustomUserDetails principal) {
        Reservation reservation = reservationFacade.holdMultiple(
                request.strategy(), request.seatIds(), principal.getMemberId());
        return ResponseEntity.ok(Map.of(
                "reservationNo", reservation.getReservationNo(),
                "amount", reservation.getAmount(),
                "holdExpiresAt", reservation.getHoldExpiresAt()
        ));
    }

    public record MultiSeatHoldRequest(List<Long> seatIds, HoldStrategy strategy) {}

    @PostMapping("/reservations/{reservationNo}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String reservationNo,
                                    @AuthenticationPrincipal CustomUserDetails principal) {
        int refund = reservationService.cancel(reservationNo, principal.getMemberId());
        return ResponseEntity.ok(Map.of("refundAmount", refund));
    }

    /**
     * 배치를 기다리지 않고 수동으로 공공데이터를 당겨올 때. 소스별 결과를 분리해서 반환한다.
     * KOPIS 는 기본이 증분 수집이라, 전체를 다시 받으려면 {@code ?full=true} 를 붙인다.
     */
    @PostMapping("/admin/sync")
    public ResponseEntity<?> sync(@RequestParam(defaultValue = "false") boolean full) {
        PerformanceSyncScheduler.SyncSummary summary = syncScheduler.runSync(full);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("standard", toResponse(summary.standard()));
        body.put("culture", toResponse(summary.culture()));
        body.put("kopis", toResponse(summary.kopis()));
        body.put("totalCreated", summary.totalCreated());
        body.put("kopisMode", summary.kopisIncremental() ? "INCREMENTAL" : "FULL");
        body.put("kopisAfterDate", summary.kopisAfterDate());
        return ResponseEntity.ok(body);
    }

    /**
     * 기존 회차의 좌석을 현재 생성 규칙으로 다시 만든다. 규칙을 바꿔도 이미 쌓인 데이터에는
     * 반영되지 않으므로 필요할 때 수동으로 호출한다. 예매가 있는 회차는 건너뛴다.
     */
    @PostMapping("/admin/seats/regenerate")
    public ResponseEntity<?> regenerateSeats() {
        SeatRegenerationService.Result result = seatRegenerationService.regenerateAll();
        return ResponseEntity.ok(Map.of(
                "scheduleCount", result.scheduleCount(),
                "rebuilt", result.rebuilt(),
                "skipped", result.skipped(),
                "seatsBefore", result.seatsBefore(),
                "seatsAfter", result.seatsAfter()
        ));
    }

    private Map<String, Object> toResponse(PerformanceSyncScheduler.SourceSyncResult result) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("received", result.received());
        body.put("created", result.created());
        body.put("skipped", result.skipped());
        body.put("success", result.success());
        if (!result.success()) {
            body.put("error", result.error());
        }
        return body;
    }

    @ExceptionHandler(SeatAlreadyTakenException.class)
    public ResponseEntity<?> handleTaken(SeatAlreadyTakenException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(PartialSeatHoldException.class)
    public ResponseEntity<?> handlePartialTaken(PartialSeatHoldException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage(), "failedSeatIds", e.getFailedSeatIds()));
    }

    @ExceptionHandler(PurchaseLimitExceededException.class)
    public ResponseEntity<?> handlePurchaseLimit(PurchaseLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TooManyHoldAttemptsException.class)
    public ResponseEntity<?> handleTooManyAttempts(TooManyHoldAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<?> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
