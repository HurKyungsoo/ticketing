package com.portfolio.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "member",
    uniqueConstraints = @UniqueConstraint(name = "uk_member_login_id", columnNames = "loginId")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 폼 가입은 사용자가 정한 짧은 아이디지만, 소셜 가입은 {@code "<제공자>_<providerId>"} 다
     * (CustomOAuth2UserService). {@link #providerId} 가 50 자까지이므로 여기는 최소 56 자가
     * 필요하다 — 30 이던 시절 네이버 신규 가입이 "Data too long" 으로 전부 실패했다.
     */
    @Column(nullable = false, length = 64)
    private String loginId;

    /** BCrypt 인코딩된 값만 저장한다. */
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 20)
    private String nickname;

    /**
     * 로컬 계정 비밀번호 재설정·예매 알림에 쓴다. 카카오/네이버는 지금 요청 scope 에
     * 이메일이 없어(profile_nickname / name) OAuth 계정은 계속 null 이다.
     */
    @Column(length = 190)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MemberRole role = MemberRole.USER;

    /**
     * 카카오/네이버 로그인 계정도 password 는 채워둔다(랜덤값 인코딩) — 폼 로그인 인증 흐름과 스키마를 그대로 공유하기 위함.
     * columnDefinition 에 DEFAULT 를 명시한 이유: 이미 회원이 있는 로컬 DB 에 ddl-auto=update 로
     * 이 컬럼을 처음 추가할 때, 기본값 없이 NOT NULL 컬럼을 추가하면 H2 가 기존 행 때문에
     * "NULL not allowed" 로 ALTER TABLE 자체를 거부한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'LOCAL'")
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    /** 소셜 로그인 제공자가 발급한 사용자 식별자. LOCAL 계정은 null. */
    @Column(length = 50)
    private String providerId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 비밀번호 재설정에서만 쓴다. 인자는 항상 인코딩된 값이어야 한다. */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
