package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비밀번호 재설정 링크의 토큰. member 와 FK 를 안 거는 이유는 wishlist·reservation 과 같다
 * (수집·정리 배치가 member 를 직접 지우는 경로는 없지만, 표를 맞춰둔다).
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 한 번도 안 썼고 만료 전이어야 유효하다. */
    public boolean isValid(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(LocalDateTime now) {
        this.usedAt = now;
    }
}
