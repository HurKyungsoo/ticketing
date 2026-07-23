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

    /** 결제 페이지용. open-in-view=false 라 seat->schedule->performance 를 미리 가져와야 한다. */
    @Query("select r from Reservation r " +
            "join fetch r.seat s " +
            "join fetch s.schedule sc " +
            "join fetch sc.performance " +
            "where r.reservationNo = :reservationNo")
    Optional<Reservation> findWithSeatDetailsByReservationNo(@Param("reservationNo") String reservationNo);

    List<Reservation> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    List<Reservation> findByStatusAndHoldExpiresAtBefore(ReservationStatus status, LocalDateTime now);

    long countBySeatIdAndStatusIn(Long seatId, List<ReservationStatus> statuses);
}
