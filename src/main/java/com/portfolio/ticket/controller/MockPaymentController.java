package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.Seat;
import com.portfolio.ticket.repository.ReservationRepository;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.ForbiddenException;
import com.portfolio.ticket.service.NotFoundException;
import com.portfolio.ticket.service.ReservationService;
import com.portfolio.ticket.service.ShareMetaView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

/**
 * 테스트용 즉시 결제. 토스 결제창을 거치지 않고 예매를 바로 확정한다.
 *
 * <p>왜 필요한가: 결제 다음 화면들(완료·마이페이지 확정 건·취소·환불)을 확인하려면 매번
 * 토스 테스트 결제창을 통과해야 하는데, 그러려면 발급받은 키가 환경변수에 있어야 한다.
 * 키 없이 띄운 로컬에서는 결제 버튼 자체가 동작하지 않아 그 화면들을 전혀 볼 수 없었다.
 *
 * <p><b>이 클래스는 {@code toss.mock-enabled=true} 일 때만 빈으로 등록된다.</b>
 * 컨트롤러 안에서 플래그를 보고 분기하지 않고 클래스째 조건부로 둔 이유는, 런타임 분기는
 * 조건문 하나만 잘못 건드려도 살아나지만 빈이 없으면 URL 이 매핑조차 안 되기 때문이다
 * (꺼진 환경에서는 404). 켜는 곳은 application.yml 의 local 프로파일 한 곳뿐이다.
 *
 * <p>우회하는 것은 <b>결제 수단 승인(토스 API 호출)뿐</b>이다. 소유자 확인, 예매 상태,
 * 좌석 선점 만료 검사는 실제 결제 경로({@code PaymentController.success})와 똑같이 거친다 —
 * 여기서 그 검사까지 빼면 이 경로로 만든 데이터가 정상 흐름으로는 나올 수 없는 상태가 되어,
 * 정작 확인하려던 다음 화면들을 잘못된 전제로 보게 된다.
 */
@Slf4j
@Controller
@RequestMapping("/reservations/{reservationNo}/payment")
@ConditionalOnProperty(prefix = "toss", name = "mock-enabled", havingValue = "true")
@RequiredArgsConstructor
public class MockPaymentController {

    /** 결제 키 자리에 남기는 값. 실제 토스 결제 건과 구별되도록 접두어를 붙인다. */
    private static final String MOCK_KEY_PREFIX = "MOCK-";

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ShareMetaView shareMetaView;

    /**
     * 즉시 확정. 상태를 바꾸므로 POST 다(CSRF 토큰은 폼이 함께 보낸다).
     *
     * <p>확정 후 곧바로 뷰를 그리지 않고 리다이렉트하는 이유는 새로고침 때문이다 —
     * POST 응답으로 완료 화면을 그리면 F5 가 같은 POST 를 다시 쏘고, 그때는 이미
     * CONFIRMED 라 확정에 실패해 완료 화면 대신 오류가 뜬다.
     */
    @PostMapping("/mock-confirm")
    public String confirm(@PathVariable String reservationNo,
                          @AuthenticationPrincipal CustomUserDetails principal) {
        Reservation reservation = getOwnedReservation(reservationNo, principal);

        // 이미 확정된 건이면 다시 확정하지 않고 결과 화면으로 보낸다. 이 버튼은 평범한 폼
        // 제출이라 더블클릭이나 뒤로가기 후 재제출로 두 번째 POST 가 실제로 들어온다 —
        // 그때 예외를 던지면 결제는 멀쩡히 끝났는데 사용자는 500 을 본다.
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return redirectToResult(reservationNo);
        }

        // 실제 결제 경로의 조기 차단과 같은 검사. 만료된 선점을 확정하면 남이 이미 산
        // 좌석을 덮어쓸 수 있다.
        if (reservation.getStatus() != ReservationStatus.PENDING
                || reservation.isHoldExpired(LocalDateTime.now())) {
            throw new IllegalStateException("좌석 선점이 만료되어 결제를 진행할 수 없습니다.");
        }

        String paymentKey = MOCK_KEY_PREFIX + UUID.randomUUID();
        reservationService.confirmPayment(reservationNo, paymentKey);
        log.warn("테스트 즉시 결제로 예매를 확정했다. 실제 결제는 일어나지 않았다. reservationNo={}, paymentKey={}",
                reservationNo, paymentKey);

        return redirectToResult(reservationNo);
    }

    private String redirectToResult(String reservationNo) {
        return "redirect:/reservations/" + reservationNo + "/payment/mock-success";
    }

    /**
     * 확정 결과 화면. 실제 결제 완료 화면과 같은 템플릿을 쓴다 — 테스트 모드에서 확인하려는
     * 게 바로 이 화면이라, 여기만 다르게 생기면 확인하는 의미가 없다.
     */
    @GetMapping("/mock-success")
    public String success(@PathVariable String reservationNo,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          Model model) {
        Reservation reservation = getOwnedReservation(reservationNo, principal);

        // 확정된 건이 아니면 완료 화면을 보여주지 않는다. 이 URL 을 직접 치거나 확정 전
        // 주소를 북마크해 두면 아직 결제 안 한 예매에 "예매가 확정되었습니다" 가 뜬다 —
        // 결제 화면으로 돌려보내면 거기서 실제 상태(결제 대기/만료)를 그대로 말해준다.
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            return "redirect:/reservations/" + reservationNo + "/payment";
        }

        model.addAttribute("reservation", reservation);
        model.addAttribute("seats", reservation.getSeats().stream()
                .sorted(Comparator.comparing(Seat::getId)).toList());
        // 실제 결제 경로(PaymentController.success)와 같은 값을 넣어야 한다 — 여기만 빠지면
        // 시트는 열리는데 공유할 내용이 비어 나가고, 테스트 모드에서 확인이 안 된다.
        shareMetaView.addPerformanceShare(model,
                reservation.getSchedule().getPerformance(), reservation.getSchedule().getShowAt());
        return "reservation/payment-success";
    }

    /** PaymentController 와 같은 규칙 — 남의 예매는 존재 여부도 알려주지 않는다. */
    private Reservation getOwnedReservation(String reservationNo, CustomUserDetails principal) {
        Reservation reservation = reservationRepository.findWithSeatDetailsByReservationNo(reservationNo)
                .orElseThrow(() -> new NotFoundException("예매 내역을 찾을 수 없습니다."));
        if (!reservation.getMemberId().equals(principal.getMemberId())) {
            throw new ForbiddenException("본인 예매만 결제할 수 있습니다.");
        }
        return reservation;
    }
}
