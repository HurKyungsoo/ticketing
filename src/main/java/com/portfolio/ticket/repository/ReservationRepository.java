package com.portfolio.ticket.repository;

import com.portfolio.ticket.domain.Reservation;
import com.portfolio.ticket.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByReservationNo(String reservationNo);

    List<Reservation> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    List<Reservation> findByStatusAndHoldExpiresAtBefore(ReservationStatus status, LocalDateTime now);

    long countBySeatIdAndStatusIn(Long seatId, List<ReservationStatus> statuses);
}
