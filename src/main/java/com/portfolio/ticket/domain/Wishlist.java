package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 찜한 공연.
 *
 * <p>회차가 아니라 <b>공연</b>에 건다. 찜은 "나중에 볼까" 하는 표시라 관람일을 아직 안 정한
 * 단계이고, 회차에 걸면 그 회차가 지나는 순간 찜이 의미를 잃는다.
 *
 * <p>{@code memberId} 를 연관관계가 아니라 값으로 든 건 {@link Reservation} 과 같은 이유다 —
 * 회원 정보를 따라갈 일이 없고, 목록 조회에서 공연만 fetch 하면 되기 때문이다.
 */
@Entity
@Table(
    name = "wishlist",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_wishlist", columnNames = {"member_id", "performance_id"}),
    indexes = @Index(name = "idx_wishlist_member", columnList = "member_id, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Wishlist {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performance_id")
    private Performance performance;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
