package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import com.portfolio.ticket.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationNo(String reservationNo);

    /**
     * 결제 페이지용. open-in-view=false 라 화면에서 쓸 연관관계를 미리 가져와야 한다.
     *
     * <p><b>좌석은 left join 이어야 한다.</b> 선점이 만료되면 {@link Seat#release()} 가 좌석
     * 연결을 끊어 좌석이 0개가 되는데, inner join 이면 그 예매가 결과에서 통째로 빠져
     * 결제 페이지가 404 가 된다 — 정작 "선점이 만료됐다"고 알려줘야 할 상황에서 아무것도
     * 설명하지 못한다. (마이페이지 조회에서 이미 같은 함정을 겪었다.)
     *
     * <p>공연 정보는 좌석이 아니라 예매가 직접 든 회차에서 가져온다. 같은 이유로 좌석을
     * 거치면 만료·취소된 예매에서 공연명·일시를 잃는다.
     *
     * <p>좌석이 여러 개면 join fetch 로 행이 좌석 수만큼 뻥튀기되므로 distinct 로 접어준다.
     */
    @Query("select distinct r from Reservation r " +
            "join fetch r.schedule sc " +
            "join fetch sc.performance " +
            "left join fetch r.seats " +
            "where r.reservationNo = :reservationNo")
    Optional<Reservation> findWithSeatDetailsByReservationNo(@Param("reservationNo") String reservationNo);

    /**
     * 마이페이지 예매 내역용.
     *
     * <p>좌석을 거쳐 공연 정보를 가져오면 안 된다. 취소/만료된 예매는 {@link Seat#release()} 가
     * 좌석 연결을 끊어 좌석이 0개가 되는데, {@code join fetch r.seats} 는 inner join 이라
     * 그 예매가 결과에서 통째로 빠진다 — 화면에 취소 배지 표시까지 만들어 놨는데도 취소 내역이
     * 영구히 안 보였다. 회차는 예매가 직접 들고 있으므로 그쪽으로 fetch 한다.
     *
     * <p>좌석 표기는 {@code seatSummary} 스냅샷을 쓰므로 좌석을 fetch 하지 않는다.
     * 컬렉션을 안 당겨오니 행이 늘지 않아 {@code distinct} 도 필요없다.
     */
    @Query("select r from Reservation r " +
            "join fetch r.schedule sc " +
            "join fetch sc.performance " +
            "where r.memberId = :memberId " +
            "order by r.createdAt desc")
    List<Reservation> findWithScheduleByMemberIdOrderByCreatedAtDesc(@Param("memberId") Long memberId);

    List<Reservation> findByStatusAndHoldExpiresAtBefore(ReservationStatus status, LocalDateTime now);
}
