package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationNo(String reservationNo);

    /**
     * 결제 페이지용. open-in-view=false 라 seat->schedule->performance 를 미리 가져와야 한다.
     * 좌석이 여러 개인 예매는 join fetch 로 행이 좌석 수만큼 뻥튀기되므로 distinct 로 접어준다.
     */
    @Query("select distinct r from Reservation r " +
            "join fetch r.seats s " +
            "join fetch s.schedule sc " +
            "join fetch sc.performance " +
            "where r.reservationNo = :reservationNo")
    Optional<Reservation> findWithSeatDetailsByReservationNo(@Param("reservationNo") String reservationNo);

    /** 마이페이지 예매 내역용. 좌석 join fetch 로 인한 행 뻥튀기를 distinct 로 접는다. */
    @Query("select distinct r from Reservation r " +
            "join fetch r.seats s " +
            "join fetch s.schedule sc " +
            "join fetch sc.performance " +
            "where r.memberId = :memberId " +
            "order by r.createdAt desc")
    List<Reservation> findWithSeatDetailsByMemberIdOrderByCreatedAtDesc(@Param("memberId") Long memberId);

    List<Reservation> findByStatusAndHoldExpiresAtBefore(ReservationStatus status, LocalDateTime now);
}
